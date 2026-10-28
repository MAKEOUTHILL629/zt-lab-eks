#!/usr/bin/env bash
# Instala la plataforma del laboratorio sobre el clúster ya aprovisionado (etapa infra).
# Orden importante: bajo el modo estricto de Amazon VPC CNI todo pod arranca denegado,
# así que lo primero es abrir los espacios de plataforma para que CoreDNS funcione.
#
# Variables requeridas (las exporta lab.sh desde las salidas de tofu):
#   REGION, CUENTA, ID_VPC   ·   opcional: CLUSTER (por defecto zt-lab)
set -euo pipefail

: "${REGION:?falta REGION}"
: "${CUENTA:?falta CUENTA}"
: "${ID_VPC:?falta ID_VPC}"
CLUSTER="${CLUSTER:-zt-lab}"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Versiones fijadas (coinciden con la sección 5.3 y el Anexo F de la monografía).
VER_GWAPI="v1.5.0"
VER_CONTROLADOR="v2.1.4"
VER_CERTMANAGER="v1.21.3"
VER_KPS="92.14.1"

echo ">> 1/7 Espacios de nombres de plataforma"
for ns in cert-manager aws-application-networking-system observabilidad; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
done

echo ">> 2/7 Políticas de permiso de plataforma (modo estricto: recuperan CoreDNS)"
kubectl apply -f "$AQUI/00-politicas-plataforma.yaml"
kubectl -n kube-system rollout status ds/aws-node --timeout=120s || true

echo ">> 3/7 CRD estándar de Gateway API ${VER_GWAPI} (incluye TLSRoute)"
kubectl apply --server-side -f \
  "https://github.com/kubernetes-sigs/gateway-api/releases/download/${VER_GWAPI}/standard-install.yaml"

echo ">> 4/7 cert-manager ${VER_CERTMANAGER}"
kubectl apply -f \
  "https://github.com/cert-manager/cert-manager/releases/download/${VER_CERTMANAGER}/cert-manager.yaml"
kubectl -n cert-manager rollout status deploy/cert-manager-webhook --timeout=180s

echo ">> 5/7 AWS Gateway API Controller ${VER_CONTROLADOR}"
# Con IMDSv2 y límite de saltos 1 el controlador no infiere cuenta/región/VPC: se dan.
# La identidad la aporta la asociación de EKS Pod Identity creada en la etapa infra.
helm install gateway-api-controller \
  oci://public.ecr.aws/aws-application-networking-k8s/aws-gateway-controller-chart \
  --version="${VER_CONTROLADOR}" \
  --namespace aws-application-networking-system \
  --set=awsRegion="${REGION}" \
  --set=awsAccountId="${CUENTA}" \
  --set=clusterVpcId="${ID_VPC}" \
  --set=clusterName="${CLUSTER}" \
  --set=log.level=info \
  --set=webhookEnabled=false \
  --wait --timeout 5m
kubectl apply -f "$AQUI/gatewayclass.yaml"

echo ">> 6/7 kube-prometheus-stack ${VER_KPS} (Prometheus + Grafana OSS)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community >/dev/null
helm install observabilidad prometheus-community/kube-prometheus-stack \
  --version "${VER_KPS}" \
  --namespace observabilidad \
  --values "$AQUI/kps-values.yaml" \
  --wait --timeout 10m

echo ">> 7/7 Tablero y regla de alerta de Grafana"
kubectl -n observabilidad create configmap zt-dashboard \
  --from-file=zt-lab.json="$AQUI/dashboard/zt-lab.json" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n observabilidad label configmap zt-dashboard grafana_dashboard=1 --overwrite
kubectl -n observabilidad create configmap zt-alerta \
  --from-file=zt-lab.yaml="$AQUI/alerta-grado.yaml" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n observabilidad label configmap zt-alerta grafana_alert=1 --overwrite

echo ">> Plataforma lista."
