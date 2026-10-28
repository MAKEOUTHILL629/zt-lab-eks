# Laboratorio Zero Trust para tráfico Este-Oeste en Amazon EKS

Infraestructura como código y aplicación del modelo de seguridad Zero Trust descrito en
la monografía *Modelo de seguridad Zero Trust para la mitigación de ataques de
movimiento lateral en el tráfico Este-Oeste de microservicios sobre Amazon EKS*
(Universidad Distrital Francisco José de Caldas). El laboratorio reproduce exactamente
el alcance del trabajo: capa preventiva (Amazon VPC Lattice + IAM + mTLS) y capa de
detección (servicio de auditoría reactivo con Random Cut Forest).

> **Laboratorio efímero y de pago por uso.** Todo se aprovisiona al inicio y se destruye
> al final de cada sesión. El costo estimado es de **≈ US$ 90** para el conjunto de
> sesiones de desarrollo y experimentación (sección 6.6.2 de la monografía). Destruya el
> laboratorio al terminar (`./lab.sh destruir`).

## Contenido

| Carpeta | Contenido |
|---|---|
| `infra/` | VPC, clúster EKS 1.36, IAM + EKS Pod Identity, registros, red y presupuesto (OpenTofu). |
| `politicas/` | Tercer nivel de políticas de identidad (portal, pedidos) y CNAME de pagos (OpenTofu). |
| `plataforma/` | CRDs de Gateway API, AWS Gateway API Controller, cert-manager, kube-prometheus-stack, tablero y alerta. |
| `k8s/` | Manifiestos: `base/` (común), `modelo/` (capa C1), `c0/` (línea base), `c1/` (pods comprometidos y destinos). |
| `apps/` | Microservicios Java 17 / Spring Boot 3.5 (catalogo, clientes, pedidos, pagos) y servicio de auditoría con RCF. |
| `portal/`, `ataques/`, `analisis/` | Generador de tráfico, escenarios E1-E8 y análisis en Python. |
| `.github/workflows/ci.yml` | Integración continua: compila, prueba y publica las siete imágenes en GHCR. |
| `lab.sh` | Orquestación del laboratorio por etapas. |

## Requisitos previos

1. **Cuenta de AWS con VPC Lattice habilitado.** VPC Lattice no está disponible en las
   cuentas nuevas con el plan gratuito a menos que se activen las *funciones avanzadas*
   (o se pase al plan de pago). Verifique en la consola de AWS → Billing/Plan antes de
   desplegar. El laboratorio usa la región `us-east-1`.
2. **Herramientas** (se recomienda **AWS CloudShell**, que ya las trae casi todas):
   `aws`, `kubectl`, `helm`, `git`, `python3` y **OpenTofu** (`tofu`) o Terraform.
3. **Créditos/controles de costo.** Configure, si lo desea, el correo de alerta de
   AWS Budgets (variable `correo_presupuesto` en `infra/variables.tf`).

## Despliegue

```bash
# Propietario de las imágenes del contenedor en GitHub (en minúsculas).
export PROPIETARIO=<usuario-u-organizacion-de-github>

# Configuración C1 (modelo completo), de principio a fin:
./lab.sh todo c1
# o, para la línea base del experimento:
./lab.sh todo c0
```

`lab.sh todo` encadena las etapas; también puede ejecutarlas por separado para
inspeccionar cada paso:

```bash
./lab.sh infra         # tofu apply en infra/
./lab.sh kubeconfig    # acceso kubectl al clúster
./lab.sh plataforma    # controlador, cert-manager, observabilidad
./lab.sh desplegar c1  # aplicación + modelo (dos fases)
./lab.sh politicas     # tercer nivel de identidad y CNAME de pagos
```

### Imágenes de contenedor

La integración continua publica las imágenes en `ghcr.io/$PROPIETARIO/zt-*`. Como GHCR
las crea privadas, antes de desplegar en el clúster hágalas **públicas** (GitHub →
Packages → cada paquete → *Package settings* → *Change visibility*), o cree un
*image pull secret* en los espacios `tienda` y `auditoria`. Fije `ETIQUETA` al SHA
publicado si no quiere usar `latest` (`export ETIQUETA=<sha>`).

### Modo estricto de red

El complemento Amazon VPC CNI se despliega en **modo estricto**: bajo este modo todo pod
arranca con denegación por defecto y necesita una política de red para cada destino,
**incluido CoreDNS**. Por eso `plataforma/00-politicas-plataforma.yaml` abre los espacios
de plataforma (kube-system, cert-manager, red del controlador y observabilidad) y la
línea base C0 aplica una política "sin restricciones" en `tienda`. El alcance Zero Trust
del trabajo es el tráfico Este-Oeste de la tienda.

## Experimentos (OE4)

Con C1 desplegado y en calentamiento (≥ 2 h de tráfico legítimo del portal):

```bash
# Prueba de alcance lateral desde el pod comprometido (sección 7.4.3):
kubectl exec -n tienda deploy/atacante-catalogo -- python alcance.py destinos.json

# Escenarios de ataque (E1-E8):
kubectl exec -n tienda deploy/atacante-catalogo -- python escenarios.py E6
kubectl exec -n tienda deploy/atacante-pedidos  -- python escenarios.py E7

# Tablero de Grafana:
kubectl -n observabilidad port-forward svc/observabilidad-grafana 3000:80
# usuario admin / contraseña: ver plataforma/kps-values.yaml (cámbiela).
```

El análisis de sensibilidad al umbral se ejecuta sobre los datos exportados:

```bash
cd analisis && python analisis_umbral.py   # requiere puntajes.csv y bitacora_*.jsonl
```

## Teardown

```bash
./lab.sh destruir
```

Elimina primero las rutas y el Gateway (para que el controlador libere los recursos de
VPC Lattice y la asociación de la VPC) y luego ejecuta `tofu destroy` en `politicas/` e
`infra/`. Verifique en la consola que no queden servicios de VPC Lattice huérfanos.

## Pruebas

- **Java:** `cd apps && mvn -B verify` (JUnit 5, Mockito, StepVerifier; pruebas P1-P8
  del servicio de auditoría).
- **Python:** `pip install "botocore[crt]" requests pandas numpy matplotlib pytest` y
  `pytest portal ataques analisis`.

La integración continua ejecuta ambas en cada cambio.
