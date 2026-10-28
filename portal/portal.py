"""Generador de tráfico legítimo L0 del portal (sección 7.4.1).

Proceso de llegadas de Poisson con media ZT_TASA solicitudes/segundo:
  60 % consulta de catálogo, 30 % creación de pedido (que desencadena consultas a
  catálogo y clientes y un pago), 10 % consulta de pedido.
En C1 (ZT_MODO=c1) firma cada solicitud con SigV4; en C0 llama por HTTP plano.
"""
import os
import random
import sys
import time

from firmar import solicitud

MODO = os.environ.get("ZT_MODO", "c0")
TASA = float(os.environ.get("ZT_TASA", "5"))
FIRMAR = MODO == "c1"
DESTINOS = {
    "catalogo": os.environ.get("CATALOGO_URL", ""),
    "pedidos": os.environ.get("PEDIDOS_URL", ""),
}


def elegir_accion(u):
    """Acción según un uniforme u en [0,1): mezcla 60/30/10 (función pura)."""
    if u < 0.60:
        return "ver_catalogo"
    if u < 0.90:
        return "crear_pedido"
    return "ver_pedido"


def intervalo(azar):
    """Tiempo entre llegadas (exponencial) para el proceso de Poisson."""
    return azar.expovariate(TASA)


def _ejecutar(accion, azar):
    try:
        if accion == "ver_catalogo":
            solicitud("GET", DESTINOS["catalogo"] + "/productos", firmar=FIRMAR, verify=False)
        elif accion == "crear_pedido":
            cuerpo = ('{"cliente":%d,"producto":%d,"cantidad":%d}'
                      % (azar.randint(1, 500), azar.randint(1, 100), azar.randint(1, 3)))
            solicitud("POST", DESTINOS["pedidos"] + "/pedidos", cuerpo=cuerpo, firmar=FIRMAR, verify=False)
        else:
            solicitud("GET", DESTINOS["pedidos"] + "/pedidos/" + str(azar.randint(1, 50)),
                      firmar=FIRMAR, verify=False)
    except Exception:  # noqa: BLE001  el portal no debe caerse por un error puntual
        pass


def main(semilla=0):
    azar = random.Random(semilla)
    while True:
        _ejecutar(elegir_accion(azar.random()), azar)
        time.sleep(intervalo(azar))


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 0)
