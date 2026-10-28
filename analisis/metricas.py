"""Métricas de nivel 1 (mitigación) y nivel 2 (algoritmo) a partir de la verdad de
terreno y de los puntajes (sección 6.5). Funciones puras sobre DataFrames de pandas.
"""
import pandas as pd


def tasa_bloqueo(bitacora):
    """TB = intentos observados como denegados / intentos cuyo esperado es 'denegado'."""
    inyectados = bitacora[bitacora["esperado"] == "denegado"]
    if inyectados.empty:
        return float("nan")
    observado = inyectados["observado"].astype(str)
    bloqueados = observado.str.startswith("4") | observado.str.contains("Error|Timeout|Refused", case=False)
    return 100.0 * bloqueados.mean()


def alcance_lateral(resultado_alcance):
    """ALR = número de servicios HTTP alcanzables (dict nombre->bool del script alcance)."""
    return sum(1 for k, v in resultado_alcance.items() if k != "imds" and v)


def cobertura_politica(servicios_protegidos, servicios_totales):
    """CP = porcentaje de servicios con política activa."""
    return 100.0 * servicios_protegidos / servicios_totales if servicios_totales else 0.0


def desempeno_por_escenario(puntajes_etiquetados, umbral):
    """Precisión, exhaustividad y F1 por escenario para un umbral dado."""
    p = puntajes_etiquetados.copy()
    p["alerta"] = p["puntaje"] > umbral
    filas = []
    for escenario, g in p.groupby(p.get("escenario", "global")):
        vp = int((g["alerta"] & g["positiva"]).sum())
        fp = int((g["alerta"] & ~g["positiva"]).sum())
        fn = int((~g["alerta"] & g["positiva"]).sum())
        prec = vp / (vp + fp) if vp + fp else 0.0
        exh = vp / (vp + fn) if vp + fn else 0.0
        f1 = 2 * prec * exh / (prec + exh) if prec + exh else 0.0
        filas.append((escenario, vp, fp, fn, prec, exh, f1))
    return pd.DataFrame(filas, columns=["escenario", "vp", "fp", "fn", "precision", "exhaustividad", "f1"])
