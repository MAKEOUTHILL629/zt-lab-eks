"""Solicitudes firmadas con SigV4 hacia VPC Lattice ({lst:e_firmar}, Anexo E).

Dependencias: pip install "botocore[crt]" requests
El mismo módulo lo usa el generador de tráfico legítimo del portal.
"""
import botocore.session
import requests
from botocore import crt
from botocore.awsrequest import AWSRequest

REGION = "us-east-1"
_sesion = botocore.session.Session()  # credenciales de EKS Pod Identity del pod


def solicitud(metodo, url, cuerpo=None, firmar=True, timeout=5, **kwargs):
    """Envía la solicitud firmada; con firmar=False la envía anónima (escenario E2)."""
    cabeceras = {"Content-Type": "application/json"}
    if firmar:
        cabeceras["x-amz-content-sha256"] = "UNSIGNED-PAYLOAD"
        peticion = AWSRequest(method=metodo, url=url, data=cuerpo, headers=cabeceras)
        peticion.context["payload_signing_enabled"] = False
        firmador = crt.auth.CrtSigV4Auth(_sesion.get_credentials(),
                                         "vpc-lattice-svcs", REGION)
        firmador.add_auth(peticion)
        cabeceras = dict(peticion.prepare().headers)
    return requests.request(metodo, url, headers=cabeceras, data=cuerpo,
                            timeout=timeout, **kwargs)
