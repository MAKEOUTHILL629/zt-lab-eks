"""Escenarios de ataque E1-E8 desde el pod comprometido (sección 7.4.2, {lst:e_e6}).

Cada escenario registra sus intentos en una bitácora JSONL (verdad de terreno) con la
estructura de la sección 6.4. Los destinos (dominios de VPC Lattice o nombres DNS) se
toman de las variables CATALOGO_URL/CLIENTES_URL/PEDIDOS_URL/PAGOS_URL del ConfigMap
destinos. La identidad comprometida se indica en ZT_IDENTIDAD (p. ej. tienda/catalogo).

Uso: python escenarios.py E6 [semilla]
"""
import json
import os
import random
import socket
import sys
import time
from datetime import datetime, timezone

import requests

from firmar import solicitud

SERVICIOS = ["catalogo", "clientes", "pedidos", "pagos"]
RUTAS = ["/clientes/{id}", "/pedidos/{id}", "/productos", "/pagos/{id}", "/admin"]
DIR_TRABAJO = os.environ.get("ZT_TRABAJO", "/trabajo")
IDENTIDAD = os.environ.get("ZT_IDENTIDAD", "tienda/catalogo")


# --------------------------------------------------------------- funciones puras
def destinos_desde_entorno(entorno=None):
    """Mapa servicio -> URL base, a partir del entorno (ConfigMap destinos)."""
    e = entorno if entorno is not None else os.environ
    return {s: e.get(s.upper() + "_URL", "") for s in SERVICIOS}


def plan(semilla, n):
    """Plan determinista de n pares (servicio, ruta) para una semilla dada."""
    azar = random.Random(semilla)
    pares = []
    for _ in range(n):
        servicio = azar.choice(sorted(SERVICIOS))
        ruta = azar.choice(RUTAS).format(id=azar.randint(1, 500))
        pares.append((servicio, ruta))
    return pares


def linea_bitacora(corrida, escenario, destino, ruta, esperado, observado):
    """Construye el registro de verdad de terreno de un intento."""
    return {
        "ts": datetime.now(timezone.utc).isoformat(),
        "corrida": corrida,
        "escenario": escenario,
        "principal": IDENTIDAD,
        "destino": destino,
        "ruta": ruta,
        "esperado": esperado,
        "observado": observado,
    }


# --------------------------------------------------------------- utilidades de E/S
def _corrida(escenario):
    return datetime.now(timezone.utc).strftime(escenario + "-%Y%m%dT%H%M%SZ")


def _bitacora(escenario):
    os.makedirs(DIR_TRABAJO, exist_ok=True)
    return open(os.path.join(DIR_TRABAJO, f"bitacora_{escenario}.jsonl"), "a", encoding="utf-8")


def _anotar(f, registro):
    f.write(json.dumps(registro) + "\n")
    f.flush()


def _invocar(destinos, servicio, ruta, firmar, timeout=5):
    url = destinos.get(servicio, "")
    if not url:
        return "sin_destino"
    try:
        return solicitud("GET", url + ruta, firmar=firmar, timeout=timeout, verify=False).status_code
    except Exception as error:  # bloqueo de red o rechazo del handshake
        return type(error).__name__


# --------------------------------------------------------------- escenarios
def ejecutar_e1(destinos):
    """E1: invocación firmada no autorizada hacia clientes y pedidos (espera 403)."""
    corrida = _corrida("E1")
    with _bitacora("E1") as f:
        for servicio in ("clientes", "pedidos"):
            obs = _invocar(destinos, servicio, "/clientes/1" if servicio == "clientes" else "/pedidos/1", True)
            _anotar(f, linea_bitacora(corrida, "E1", servicio, "firmada", "denegado", obs))


def ejecutar_e2(destinos):
    """E2: invocación anónima (sin firma) hacia clientes y pedidos (espera 403)."""
    corrida = _corrida("E2")
    with _bitacora("E2") as f:
        for servicio in ("clientes", "pedidos"):
            obs = _invocar(destinos, servicio, "/clientes/1" if servicio == "clientes" else "/pedidos/1", False)
            _anotar(f, linea_bitacora(corrida, "E2", servicio, "anonima", "denegado", obs))


def ejecutar_e3(destinos, objetivos=None):
    """E3: evasión de VPC Lattice por conexión directa a IP de pods (espera bloqueo)."""
    corrida = _corrida("E3")
    objetivos = objetivos or json.loads(os.environ.get("ZT_IPS", "{}"))  # {nombre:[ip,puerto]}
    with _bitacora("E3") as f:
        for nombre, (ip, puerto) in objetivos.items():
            try:
                with socket.create_connection((ip, int(puerto)), timeout=3):
                    obs = "conectado"
            except OSError as error:
                obs = type(error).__name__
            _anotar(f, linea_bitacora(corrida, "E3", nombre, f"{ip}:{puerto}", "denegado", obs))


def ejecutar_e4():
    """E4: robo de credenciales del nodo por el servicio de metadatos (espera bloqueo)."""
    corrida = _corrida("E4")
    with _bitacora("E4") as f:
        try:
            r = requests.put("http://169.254.169.254/latest/api/token", timeout=3,
                             headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"})
            obs = r.status_code
        except requests.RequestException as error:
            obs = type(error).__name__
        _anotar(f, linea_bitacora(corrida, "E4", "imds", "169.254.169.254", "denegado", obs))


def ejecutar_e5(destinos):
    """E5: suplantación ante pagos sin certificado de cliente autorizado (espera rechazo)."""
    corrida = _corrida("E5")
    with _bitacora("E5") as f:
        url = destinos.get("pagos", "")
        try:
            obs = requests.post(url + "/pagos", json={"pedido": 1, "montoCentavos": 100},
                                timeout=5, verify=False).status_code
        except requests.RequestException as error:
            obs = type(error).__name__
        _anotar(f, linea_bitacora(corrida, "E5", "pagos", "sin_cert_cliente", "denegado", obs))


def ejecutar_e6(destinos, semilla=7, duracion_s=60 * 60, cadencia=(45, 90)):
    """E6: low-and-slow desde identidad silenciosa durante 60 min."""
    corrida = _corrida("E6")
    azar = random.Random(semilla)
    fin = time.monotonic() + duracion_s
    with _bitacora("E6") as f:
        while time.monotonic() < fin:
            servicio = azar.choice(sorted(destinos))
            ruta = azar.choice(RUTAS).format(id=azar.randint(1, 500))
            obs = _invocar(destinos, servicio, ruta, True)
            _anotar(f, linea_bitacora(corrida, "E6", servicio, ruta, "denegado", obs))
            time.sleep(azar.uniform(*cadencia))


def ejecutar_e7(destinos, duracion_s=60 * 60, cadencia=(20, 40)):
    """E7: low-and-slow mimetizado (identidad pedidos): enumeración lenta de /clientes/{id}."""
    corrida = _corrida("E7")
    azar = random.Random(13)
    fin = time.monotonic() + duracion_s
    with _bitacora("E7") as f:
        ident = 1
        while time.monotonic() < fin:
            ruta = f"/clientes/{ident}"
            obs = _invocar(destinos, "clientes", ruta, True)  # ruta permitida para pedidos
            _anotar(f, linea_bitacora(corrida, "E7", "clientes", ruta, "permitido", obs))
            ident += 1
            time.sleep(azar.uniform(*cadencia))


def ejecutar_e8(destinos, tasa=10, duracion_s=60):
    """E8: barrido en ráfaga (tasa req/s) durante duracion_s."""
    corrida = _corrida("E8")
    fin = time.monotonic() + duracion_s
    intervalo = 1.0 / tasa
    with _bitacora("E8") as f:
        i = 0
        while time.monotonic() < fin:
            servicio, ruta = plan(i, 1)[0]
            obs = _invocar(destinos, servicio, ruta, True)
            _anotar(f, linea_bitacora(corrida, "E8", servicio, ruta, "denegado", obs))
            i += 1
            time.sleep(intervalo)


def _duracion_s():
    """Duración de E6/E7 en segundos; 60 min por defecto (ZT_DURACION_MIN la reduce)."""
    return int(float(os.environ.get("ZT_DURACION_MIN", "60")) * 60)


def main(argv):
    escenario = (argv[1] if len(argv) > 1 else "E6").upper()
    destinos = destinos_desde_entorno()
    if escenario == "E1":
        ejecutar_e1(destinos)
    elif escenario == "E2":
        ejecutar_e2(destinos)
    elif escenario == "E3":
        ejecutar_e3(destinos)
    elif escenario == "E4":
        ejecutar_e4()
    elif escenario == "E5":
        ejecutar_e5(destinos)
    elif escenario == "E6":
        ejecutar_e6(destinos, int(argv[2]) if len(argv) > 2 else 7, duracion_s=_duracion_s())
    elif escenario == "E7":
        ejecutar_e7(destinos, duracion_s=_duracion_s())
    elif escenario == "E8":
        ejecutar_e8(destinos)
    else:
        raise SystemExit(f"escenario desconocido: {escenario}")


if __name__ == "__main__":
    main(sys.argv)
