"""Prueba de alcance lateral desde el pod comprometido, en C0 y C1 ({lst:e_alcance}).

Uso: python alcance.py destinos.json
  destinos.json = {"http": {nombre: url}, "tcp": {nombre: [ip, puerto]}}
En C0 los destinos http son los nombres DNS internos de Kubernetes; en C1, los
dominios de VPC Lattice. Imprime el número de servicios alcanzables (meta en C1: 0).
"""
import json
import socket
import sys

import requests

from firmar import solicitud


def http_alcanzable(url):
    """Alcanzable si alguna variante (firmada o anónima) obtiene una respuesta exitosa."""
    for firmar in (True, False):
        try:
            r = solicitud("GET", url, firmar=firmar, timeout=3, verify=False)
            if r.status_code < 400:
                return True
        except requests.RequestException:
            pass
    return False


def tcp_alcanzable(ip, puerto):
    try:
        with socket.create_connection((ip, puerto), timeout=3):
            return True
    except OSError:
        return False


def imds_alcanzable():
    """Intenta obtener un token IMDSv2, paso previo al robo de credenciales del nodo."""
    try:
        r = requests.put("http://169.254.169.254/latest/api/token", timeout=3,
                         headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"})
        return r.status_code == 200
    except requests.RequestException:
        return False


def main(archivo):
    destinos = json.load(open(archivo))
    resultado = {n: http_alcanzable(u) for n, u in destinos.get("http", {}).items()}
    resultado.update({n: tcp_alcanzable(ip, p) for n, (ip, p) in destinos.get("tcp", {}).items()})
    resultado["imds"] = imds_alcanzable()
    print(json.dumps(resultado, indent=2))
    print("servicios alcanzables:", sum(1 for n in destinos.get("http", {}) if resultado[n]))


if __name__ == "__main__":
    main(sys.argv[1])
