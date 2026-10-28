"""Etiquetado de ventanas y sensibilidad de la detección al umbral ({lst:e_analisis}).

Lee puntajes.csv (export de GET /api/v1/puntajes: principal, inicio, puntaje, grado) y
las bitácoras de verdad de terreno (bitacora_*.jsonl), etiqueta cada ventana, calcula la
curva de precisión/exhaustividad/F1/FP-h frente al umbral y escribe sensibilidad.csv y
fig_umbral.png. Las funciones son puras para poder probarlas sin la nube.
"""
import glob
import sys

import numpy as np
import pandas as pd

VENTANA = pd.Timedelta("60s")


def etiquetar(puntajes, bitacora):
    """Marca como positiva la ventana que se solapa con un ataque de su mismo principal."""
    puntajes = puntajes.copy()
    puntajes["inicio"] = pd.to_datetime(puntajes["inicio"], utc=True)
    puntajes["positiva"] = False
    if not bitacora.empty:
        bitacora = bitacora.copy()
        bitacora["ts"] = pd.to_datetime(bitacora["ts"], utc=True)
        intervalos = (bitacora.groupby(["corrida", "principal"])["ts"]
                      .agg(["min", "max"]).reset_index())
        for _, iv in intervalos.iterrows():
            solapa = ((puntajes["principal"] == iv["principal"])
                      & (puntajes["inicio"] + VENTANA > iv["min"])
                      & (puntajes["inicio"] <= iv["max"]))
            puntajes.loc[solapa, "positiva"] = True
    return puntajes


def curva_sensibilidad(puntajes, horas_legitimas):
    """Precisión, exhaustividad, F1 y FP/h en función del umbral sobre el puntaje RCF."""
    filas = []
    for umbral in np.quantile(puntajes["puntaje"], np.linspace(0.80, 0.999, 80)):
        alerta = puntajes["puntaje"] > umbral
        vp = int((alerta & puntajes["positiva"]).sum())
        fp = int((alerta & ~puntajes["positiva"]).sum())
        fn = int((~alerta & puntajes["positiva"]).sum())
        precision = vp / (vp + fp) if vp + fp else 0.0
        exhaustividad = vp / (vp + fn) if vp + fn else 0.0
        f1 = (2 * precision * exhaustividad / (precision + exhaustividad)
              if precision + exhaustividad else 0.0)
        fp_h = fp / horas_legitimas if horas_legitimas else 0.0
        filas.append((umbral, precision, exhaustividad, f1, fp_h))
    return pd.DataFrame(filas, columns=["umbral", "precision", "exhaustividad", "f1", "fp_h"])


def main():
    puntajes = pd.read_csv("puntajes.csv")
    archivos = glob.glob("bitacora_*.jsonl")
    bitacora = (pd.concat([pd.read_json(f, lines=True) for f in archivos])
                if archivos else pd.DataFrame(columns=["ts", "corrida", "principal"]))
    puntajes = etiquetar(puntajes, bitacora)
    horas_legitimas = puntajes.loc[~puntajes["positiva"], "inicio"].nunique() / 60 or 1
    curva = curva_sensibilidad(puntajes, horas_legitimas)
    curva.to_csv("sensibilidad.csv", index=False)
    mejor = curva.loc[curva["f1"].idxmax()]
    print(f"umbral = {mejor.umbral:.3f}  F1 = {mejor.f1:.3f}  FP/h = {mejor.fp_h:.2f}")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    figura, eje = plt.subplots(figsize=(7, 4))
    for columna, nombre in [("precision", "Precisión"), ("exhaustividad", "Exhaustividad"),
                            ("f1", "F1")]:
        eje.plot(curva["umbral"], curva[columna], label=nombre)
    eje.set_xlabel("Umbral sobre el puntaje RCF")
    eje.set_ylabel("Valor de la métrica")
    eje_fp = eje.twinx()
    eje_fp.plot(curva["umbral"], curva["fp_h"], "k--", label="FP/h")
    eje_fp.set_ylabel("Falsos positivos por hora")
    figura.legend(loc="upper center", ncol=4)
    figura.tight_layout()
    figura.savefig("fig_umbral.png", dpi=200)


if __name__ == "__main__":
    sys.exit(main())
