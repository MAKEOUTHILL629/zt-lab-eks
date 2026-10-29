#!/usr/bin/env bash
# Orquestación del laboratorio Zero Trust sobre Amazon EKS.
# Requisitos en la máquina (se recomienda AWS CloudShell): aws, kubectl, helm, tofu
# (u terraform), git, python3. Credenciales de AWS ya configuradas.
#
# Subcomandos:
#   infra        Aprovisiona VPC, EKS, IAM y registros (tofu apply en infra/).
#   kubeconfig   Configura el acceso kubectl al clúster.
#   plataforma   Instala CRDs, controlador, cert-manager y observabilidad.
#   desplegar c0|c1   Despliega la aplicación en la configuración indicada.
#   politicas    Aplica el tercer nivel de identidad y el CNAME de pagos (tofu).
#   destruir     Elimina rutas/Gateway y destruye toda la infraestructura.
#   todo c0|c1   Encadena infra -> kubeconfig -> plataforma -> desplegar [-> politicas].
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOFU="${TOFU:-tofu}"
SUST="python3 $RAIZ/herramientas/sustituir.py"
ETIQUETA="${ETIQUETA:-latest}"

tiene() { command -v "$1" >/dev/null 2>&1 || { echo "falta la herramienta: $1" >&2; exit 1; }; }

cargar_salidas() {
  tiene "$TOFU"
  pushd "$RAIZ/infra" >/dev/null
  export REGION="$($TOFU output -raw region)"
  export CUENTA="$($TOFU output -raw cuenta)"
  export ID_VPC="$($TOFU output -raw id_vpc)"
  export SG_ASOCIACION="$($TOFU output -raw sg_asociacion)"
  export CLUSTER="$($TOFU output -raw nombre_cluster)"
  popd >/dev/null
  # Propietario de las imágenes en minúsculas (como en GHCR).
  : "${PROPIETARIO:?exporte PROPIETARIO=<usuario/organización de GitHub en minúsculas>}"
}

aplicar() { # archivo
  $SUST < "$1" | kubectl apply -f -
}

aplicar_dir() { # directorio
  for f in "$1"/*.yaml; do aplicar "$f"; done
}

dominio_ruta() { # tipo nombre  -> dominio asignado por el controlador (o vacío)
  kubectl get "$1" "$2" -n tienda -o json 2>/dev/null | python3 -c \
    "import json,sys;a=json.load(sys.stdin).get('metadata',{}).get('annotations',{});print(a.get('application-networking.k8s.aws/lattice-assigned-domain-name',''))"
}

esperar_dominios() {
  echo ">> Esperando a que el controlador asigne dominios de VPC Lattice (hasta 10 min)..."
  for _ in $(seq 1 60); do
    DOMINIO_CATALOGO="$(dominio_ruta httproute catalogo)"
    DOMINIO_CLIENTES="$(dominio_ruta httproute clientes)"
    DOMINIO_PEDIDOS="$(dominio_ruta httproute pedidos)"
    if [ -n "$DOMINIO_CATALOGO" ] && [ -n "$DOMINIO_CLIENTES" ] && [ -n "$DOMINIO_PEDIDOS" ]; then
      export DOMINIO_CATALOGO DOMINIO_CLIENTES DOMINIO_PEDIDOS
      echo "   catalogo=$DOMINIO_CATALOGO"; return 0
    fi
    sleep 10
  done
  echo "No se asignaron los dominios a tiempo; revise el controlador." >&2; exit 1
}

id_servicio_pagos() {
  aws vpc-lattice list-services --region "$REGION" \
    --query "items[?name=='pagos-tienda'].id | [0]" --output text
}

# En modo estricto de políticas de red CoreDNS queda aislado y su complemento no
# alcanza el estado activo; la política de la plataforma solo llega después de la
# infraestructura. Mientras tofu espera, se aplica aquí la de kube-system.
permitir_kube_system() { # pid de tofu apply
  local pid="$1" cluster region
  cluster="$($TOFU output -raw nombre_cluster 2>/dev/null)" || cluster="zt-lab"
  region="$($TOFU output -raw region 2>/dev/null)" || region="${AWS_REGION:-us-east-1}"
  while kill -0 "$pid" 2>/dev/null; do
    if aws eks update-kubeconfig --name "$cluster" --region "$region" >/dev/null 2>&1        && kubectl apply -f - >/dev/null 2>&1 <<'POL'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: permitir-todo
  namespace: kube-system
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  ingress: [{}]
  egress: [{}]
POL
    then
      echo ">> Política permitir-todo aplicada en kube-system (CoreDNS)."; return 0
    fi
    sleep 15
  done
}

cmd_infra() {
  tiene "$TOFU"; tiene git; tiene aws; tiene kubectl
  pushd "$RAIZ/infra" >/dev/null
  $TOFU init -input=false
  $TOFU apply -auto-approve &
  local pid=$!
  permitir_kube_system "$pid" || true
  wait "$pid"
  popd >/dev/null
}

cmd_kubeconfig() {
  tiene aws; tiene kubectl
  cargar_salidas
  aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION"
}

cmd_plataforma() {
  tiene kubectl; tiene helm
  cargar_salidas
  REGION="$REGION" CUENTA="$CUENTA" ID_VPC="$ID_VPC" CLUSTER="$CLUSTER" \
    bash "$RAIZ/plataforma/instalar.sh"
}

cmd_desplegar() { # c0|c1
  tiene kubectl
  cargar_salidas
  local perfil="${1:?indique c0 o c1}"
  export PERFIL="$perfil" REGION CUENTA ID_VPC SG_ASOCIACION PROPIETARIO ETIQUETA
  if [ "$perfil" = "c0" ]; then
    aplicar_dir "$RAIZ/k8s/base"
    aplicar_dir "$RAIZ/k8s/c0"
    echo ">> C0 desplegado (línea base sin modelo)."
    return 0
  fi

  # --- C1: modelo completo ---
  # los espacios de nombres deben existir antes de crear el ConfigMap destinos.
  aplicar "$RAIZ/k8s/base/00-namespaces.yaml"
  # grupo de registros que lee el servicio de auditoría con Live Tail (ZT_GRUPOS): los
  # registros de acceso de VPC Lattice, únicos que esta versión del normalizador tipa.
  local arn_grupo="arn:aws:logs:$REGION:$CUENTA:log-group"
  kubectl create configmap registros -n auditoria     --from-literal=ZT_GRUPOS="$arn_grupo:/zt-lab/lattice/acceso"     --dry-run=client -o yaml | kubectl apply -f -
  # destinos temporal para que los pods arranquen antes de conocer los dominios.
  kubectl create configmap destinos -n tienda \
    --from-literal=CATALOGO_URL="http://catalogo.tienda.svc.cluster.local:8080" \
    --from-literal=CLIENTES_URL="http://clientes.tienda.svc.cluster.local:8080" \
    --from-literal=PEDIDOS_URL="http://pedidos.tienda.svc.cluster.local:8080" \
    --from-literal=PAGOS_URL="https://pagos.zt-lab.internal:8443" \
    --dry-run=client -o yaml | kubectl apply -f -

  # modelo (sin la política de red de servicios 50, que necesita el ARN de pagos).
  for f in "$RAIZ"/k8s/modelo/00-gateway.yaml "$RAIZ"/k8s/modelo/10-certificados.yaml \
           "$RAIZ"/k8s/modelo/20-rutas.yaml "$RAIZ"/k8s/modelo/30-iam-servicios.yaml \
           "$RAIZ"/k8s/modelo/40-politicas-red.yaml; do aplicar "$f"; done
  aplicar_dir "$RAIZ/k8s/base"
  aplicar "$RAIZ/k8s/c1/20-atacantes.yaml"

  esperar_dominios
  export ID_SERVICIO_PAGOS="$(id_servicio_pagos)"
  [ -n "$ID_SERVICIO_PAGOS" ] && [ "$ID_SERVICIO_PAGOS" != "None" ] || { echo "no se encontró el servicio pagos-tienda" >&2; exit 1; }

  # destinos real (dominios de Lattice) y segunda fase de la política de red de servicios.
  aplicar "$RAIZ/k8s/c1/00-destinos.yaml"
  aplicar "$RAIZ/k8s/modelo/50-iam-red-servicios.yaml"

  # Reiniciar los consumidores de destinos para que tomen los dominios definitivos.
  kubectl -n tienda rollout restart deploy/pedidos deploy/portal \
    deploy/atacante-catalogo deploy/atacante-pedidos
  echo ">> C1 desplegado. Ejecute ahora: $0 politicas"
}

cmd_politicas() {
  tiene "$TOFU"
  pushd "$RAIZ/politicas" >/dev/null
  $TOFU init -input=false
  $TOFU apply -auto-approve
  popd >/dev/null
}

borrar_cname_pagos() {
  local zona nombre reg
  zona="$(cd "$RAIZ/infra" && $TOFU output -raw zona_privada_id 2>/dev/null)" || return 0
  nombre="pagos.$(cd "$RAIZ/infra" && $TOFU output -raw zona_privada_nombre 2>/dev/null)"
  reg="$(aws route53 list-resource-record-sets --hosted-zone-id "$zona"     --query "ResourceRecordSets[?Type=='CNAME' && Name=='${nombre}.']|[0]" --output json 2>/dev/null)" || return 0
  [ -n "$reg" ] && [ "$reg" != "null" ] || return 0
  echo ">> Eliminando CNAME residual $nombre"
  aws route53 change-resource-record-sets --hosted-zone-id "$zona"     --change-batch "{\"Changes\":[{\"Action\":\"DELETE\",\"ResourceRecordSet\":$reg}]}" || true
}

cmd_destruir() {
  tiene kubectl; tiene "$TOFU"
  cargar_salidas || true
  # Primero las rutas y el Gateway: deja que el controlador libere los recursos de
  # VPC Lattice y la asociación de la VPC antes de destruir la infraestructura.
  kubectl delete -f "$RAIZ/k8s/modelo/20-rutas.yaml" --ignore-not-found || true
  kubectl delete -f "$RAIZ/k8s/modelo/00-gateway.yaml" --ignore-not-found || true
  sleep 30
  if [ -d "$RAIZ/politicas/.terraform" ]; then
    (cd "$RAIZ/politicas" && $TOFU destroy -auto-approve) || true
  fi
  # Si el destroy de politicas falla (sus fuentes de datos ya no resuelven), el CNAME
  # de pagos queda y bloquea la zona privada: se elimina directamente.
  borrar_cname_pagos
  (cd "$RAIZ/infra" && $TOFU destroy -auto-approve)
}

cmd_todo() { # c0|c1
  local perfil="${1:?indique c0 o c1}"
  cmd_infra
  cmd_kubeconfig
  cmd_plataforma
  cmd_desplegar "$perfil"
  [ "$perfil" = "c1" ] && cmd_politicas || true
}

case "${1:-}" in
  infra) cmd_infra ;;
  kubeconfig) cmd_kubeconfig ;;
  plataforma) cmd_plataforma ;;
  desplegar) cmd_desplegar "${2:-}" ;;
  politicas) cmd_politicas ;;
  destruir) cmd_destruir ;;
  todo) cmd_todo "${2:-}" ;;
  *) echo "uso: $0 {infra|kubeconfig|plataforma|desplegar c0|c1|politicas|destruir|todo c0|c1}"; exit 1 ;;
esac
