"""Tablas de resultados del OE4 (monografía, secciones 7.4.3 y 7.4.4).

Lee la carpeta de datos que produce el orquestador del protocolo experimental y calcula,
con las funciones de metricas.py y analisis_umbral.py, las tablas 32 a 36 y la Figura 9:

  <datos>/c0, <datos>/c1   latencia_portal_<cfg>.jsonl, consumo_<cfg>.json, alcance_<cfg>.txt
  <datos>/rep1 .. repN     bitacora_E*.jsonl, puntajes.csv, alertas.json, estado.json,
                           metricas_auditoria.prom, orden.json, inicio.txt
  <datos>/cobertura.json   (opcional) política de autenticación aceptada por servicio

Uso: python resultados.py <carpeta_datos> [<carpeta_salida>]
Escribe resultados.md, sensibilidad.csv, fig_umbral.png y resumen.json.

Definiciones (sección 6.5 de la monografía):
  * Una ventana de 60 s de un principal es positiva si se solapa con un intervalo de ataque
    de la bitácora para ese principal, y negativa en caso contrario.
  * Una ventana se marca como anómala si su puntaje RCF supera el umbral elegido en la curva
    de sensibilidad (el de mayor F1). Aparte se informa el punto de operación por defecto del
    servicio (alerta cuando el grado de TRCF es mayor que cero).
  * VP/FN de un escenario son sus ventanas positivas; los FP de una fila son las ventanas
    negativas marcadas de los principales de ese escenario; la fila global suma todos.
  * Solo se consideran las ventanas posteriores a la primera con puntaje mayor que cero de
    cada principal: antes, su bosque aún no produce salida (calentamiento).
  * Los percentiles de latencia del portal se calculan sobre las muestras individuales.
"""
import glob
import json
import math
import os
import re
import sys

import numpy as np
import pandas as pd

import analisis_umbral as umbral_mod
import metricas

VENTANA = pd.Timedelta("60s")
NAN = float("nan")

GRUPOS_BLOQUEO = [("E1 y E2", ("E1", "E2")), ("E3", ("E3",)), ("E4", ("E4",)),
                  ("E5", ("E5",)), ("E6 y E8", ("E6", "E8"))]
GRUPOS_DETECCION = [("E1 y E2", ("E1", "E2")), ("E3", ("E3",)),
                    ("E6 (low-and-slow, identidad silenciosa)", ("E6",)),
                    ("E7 (low-and-slow mimetizado)", ("E7",)), ("E8 (ráfaga)", ("E8",))]
ESCENARIOS_TTA = ["E1", "E3", "E6", "E7", "E8"]
MECANISMOS = {"clientes": "Política de autenticación (403)", "pedidos": "Política de autenticación (403)",
              "pagos": "mTLS (certificado no autorizado)", "ip": "Política de red (DENY)"}


# ------------------------------------------------------------------ lectura
def leer_jsonl(ruta):
    filas = []
    with open(ruta, encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()
            if linea.startswith("{"):
                try:
                    filas.append(json.loads(linea))
                except ValueError:
                    pass
    return pd.DataFrame(filas)


def leer_bitacoras(carpeta):
    frames = [leer_jsonl(a) for a in sorted(glob.glob(os.path.join(carpeta, "bitacora_*.jsonl")))]
    frames = [f for f in frames if not f.empty]
    if not frames:
        return pd.DataFrame(columns=["ts", "corrida", "escenario", "principal", "destino",
                                     "ruta", "esperado", "observado", "origen"])
    b = pd.concat(frames, ignore_index=True)
    b["ts"] = pd.to_datetime(b["ts"], utc=True, format="ISO8601")
    b["origen"] = os.path.basename(os.path.normpath(carpeta))  # repetición de la que proviene
    return b


def leer_puntajes(carpetas):
    frames = [pd.read_csv(os.path.join(c, "puntajes.csv")) for c in carpetas
              if os.path.exists(os.path.join(c, "puntajes.csv"))]
    if not frames:
        return pd.DataFrame(columns=["principal", "inicio", "puntaje", "grado"])
    p = pd.concat(frames, ignore_index=True)
    p["inicio"] = pd.to_datetime(p["inicio"], utc=True, format="ISO8601")
    p = p[p["inicio"] > pd.Timestamp("2020-01-01", tz="UTC")]  # descarta marcas inválidas
    return p.drop_duplicates(["principal", "inicio"], keep="last").sort_values(["principal", "inicio"])


def leer_alertas(carpetas):
    filas = []
    for c in carpetas:
        ruta = os.path.join(c, "alertas.json")
        if os.path.exists(ruta):
            filas += json.load(open(ruta, encoding="utf-8"))
    a = pd.DataFrame(filas, columns=["principal", "inicio", "puntaje", "grado", "tta"])
    if a.empty:
        return a
    a["inicio"] = pd.to_datetime(a["inicio"], utc=True, format="ISO8601")
    a = a.drop_duplicates(["principal", "inicio"], keep="last")
    a["emitida"] = a["inicio"] + pd.to_timedelta(a["tta"], unit="s")
    return a


# ------------------------------------------------------------------ estadística
def resumen(valores):
    """Media, desviación estándar muestral, mínimo y máximo (ignora los valores no numéricos)."""
    v = np.array([x for x in valores if x is not None and not (isinstance(x, float) and math.isnan(x))],
                 dtype=float)
    if v.size == 0:
        return {"n": 0, "media": NAN, "de": NAN, "min": NAN, "max": NAN}
    return {"n": int(v.size), "media": float(v.mean()), "de": float(v.std(ddof=1)) if v.size > 1 else 0.0,
            "min": float(v.min()), "max": float(v.max())}


def percentiles(valores, qs=(50, 95, 99)):
    """Percentiles sobre las muestras individuales (sin agregar en histogramas)."""
    if len(valores) == 0:
        return {q: NAN for q in qs}
    return {q: float(np.percentile(valores, q)) for q in qs}


def cuantil_histograma(texto, metrica, q):
    """Cuantil q (0-1) de un histograma de Prometheus por interpolación lineal en el cubo."""
    patron = re.compile(r'^%s_bucket\{[^}]*le="([^"]+)"[^}]*\}\s+([0-9.eE+-]+)' % re.escape(metrica), re.M)
    cubos = sorted((math.inf if le in ("+Inf", "Inf") else float(le), float(n))
                   for le, n in patron.findall(texto))
    if not cubos or cubos[-1][1] == 0:
        return NAN
    objetivo = q * cubos[-1][1]
    previo_le, previo_n = 0.0, 0.0
    for le, n in cubos:
        if n >= objetivo:
            if math.isinf(le):
                return previo_le
            if n == previo_n:
                return le
            return previo_le + (le - previo_le) * (objetivo - previo_n) / (n - previo_n)
        previo_le, previo_n = le, n
    return NAN


def contador(texto, nombre):
    m = re.search(r'^%s(?:\{[^}]*\})?\s+([0-9.eE+-]+)' % re.escape(nombre), texto, re.M)
    return float(m.group(1)) if m else NAN


# ------------------------------------------------------------------ nivel 1
def bloqueado_estricto(observado):
    """Denegado de verdad: 401/403 o un fallo de conexión/handshake (no cuenta un 404)."""
    o = str(observado)
    if o.isdigit():
        return o in ("401", "403")
    return o not in ("conectado", "sin_destino")


def tasas_bloqueo(bitacora, escenarios):
    """(tasa estricta, tasa con la fórmula de metricas.py) para los escenarios indicados."""
    sub = bitacora[bitacora["escenario"].isin(escenarios) & (bitacora["esperado"] == "denegado")]
    if sub.empty:
        return NAN, NAN
    estricta = 100.0 * sub["observado"].map(bloqueado_estricto).mean()
    return estricta, metricas.tasa_bloqueo(sub)


def leer_alcance(ruta):
    """Resultado de alcance.py: dict nombre -> bool."""
    texto = open(ruta, encoding="utf-8").read()
    return json.loads(texto[texto.index("{"):texto.rindex("}") + 1])


def tabla_alcance(c0, c1):
    """Filas de la Tabla 33 y total de servicios alcanzables en cada configuración."""
    def si(d, k):
        return bool(d.get(k, False))

    def ip(d):
        return any(v for k, v in d.items() if k.startswith("ip_"))

    filas = []
    for s in ("clientes", "pedidos", "pagos"):
        filas.append((s, si(c0, s), si(c1, s), MECANISMOS[s]))
    filas.append(("IP directa de pods", ip(c0), ip(c1), MECANISMOS["ip"]))
    total = lambda d: sum(si(d, s) for s in ("clientes", "pedidos", "pagos"))
    return filas, total(c0), total(c1)


def tta_por_corrida(alertas, intervalos):
    """Segundos entre el primer intento de cada corrida y la primera alerta de su principal."""
    filas = []
    for _, iv in intervalos.iterrows():
        tta = NAN
        if not alertas.empty:
            c = alertas[(alertas["principal"] == iv["principal"])
                        & (alertas["inicio"] + VENTANA > iv["primero"])
                        & (alertas["inicio"] <= iv["ultimo"])]
            if not c.empty:
                tta = (c["emitida"].min() - iv["primero"]).total_seconds()
        filas.append((iv["escenario"], iv["corrida"], iv["principal"], tta))
    return pd.DataFrame(filas, columns=["escenario", "corrida", "principal", "tta"])


def cobertura(cobertura_json, bitacora):
    """CP = servicios con autenticación activa / servicios totales (4)."""
    if not cobertura_json:
        return NAN, "sin datos"
    protegidos = sum(1 for s in ("catalogo", "clientes", "pedidos") if cobertura_json.get(s))
    e5 = bitacora[(bitacora["escenario"] == "E5") & (bitacora["esperado"] == "denegado")]
    mtls = bool(cobertura_json.get("pagos_ruta_tls")) and (
        e5.empty or bool(e5["observado"].map(bloqueado_estricto).all()))
    protegidos += 1 if mtls else 0
    return metricas.cobertura_politica(protegidos, 4), f"{protegidos} de 4 servicios"


# ------------------------------------------------------------------ sobrecarga
def tabla_sobrecarga(carpeta, cfg):
    ruta = os.path.join(carpeta, cfg, f"latencia_portal_{cfg}.jsonl")
    lat = leer_jsonl(ruta)
    ok = lat[lat["estado"] == "ok"]["ms"].astype(float).to_numpy() if not lat.empty else np.array([])
    pc = percentiles(ok)
    consumo = json.load(open(os.path.join(carpeta, cfg, f"consumo_{cfg}.json"), encoding="utf-8"))["consumo"]
    cpu = float(np.mean(list(consumo["cpu"].values())))
    mem = float(np.mean(list(consumo["mem"].values())))
    por_accion = {}
    if not lat.empty:
        for accion, g in lat[lat["estado"] == "ok"].groupby("accion"):
            por_accion[accion] = percentiles(g["ms"].astype(float).to_numpy(), (50, 95))
    return {"p50": pc[50], "p95": pc[95], "p99": pc[99], "cpu": cpu, "mem": mem,
            "n": int(len(lat)), "errores": int((lat["estado"] != "ok").sum()) if not lat.empty else 0,
            "por_accion": por_accion}


# ------------------------------------------------------------------ nivel 2
def ventanas_listas(p):
    """Quita, por principal, las ventanas previas a la primera con puntaje mayor que cero."""
    primero = p[p["puntaje"] > 0].groupby("principal")["inicio"].min()
    return p[p["inicio"] >= p["principal"].map(primero)]


def intervalos_ataque(bitacora):
    if bitacora.empty:
        return pd.DataFrame(columns=["escenario", "corrida", "principal", "primero", "ultimo"])
    claves = ["origen"] if "origen" in bitacora.columns else []
    return (bitacora.groupby(claves + ["escenario", "corrida", "principal"])["ts"]
            .agg(primero="min", ultimo="max").reset_index())


def etiquetar_ventanas(puntajes, bitacora):
    """Positiva si la ventana se solapa con un intervalo de ataque del mismo principal."""
    p = puntajes.copy()
    p["positiva"] = False
    p["escenario"] = "L0"
    for _, iv in intervalos_ataque(bitacora).iterrows():
        m = ((p["principal"] == iv["principal"]) & (p["inicio"] + VENTANA > iv["primero"])
             & (p["inicio"] <= iv["ultimo"]))
        p.loc[m & ~p["positiva"], "escenario"] = iv["escenario"]
        p.loc[m, "positiva"] = True
    return p


def razones(vp, fp, fn):
    precision = vp / (vp + fp) if vp + fp else NAN
    exhaustividad = vp / (vp + fn) if vp + fn else NAN
    if vp + fp + fn == 0:
        f1 = NAN
    elif vp == 0:
        f1 = 0.0
    else:
        f1 = 2 * precision * exhaustividad / (precision + exhaustividad)
    return precision, exhaustividad, f1


def conteos(p, marca, principales=None, escenarios=None):
    """VP, FN y FP de las ventanas p con la columna booleana `marca` (alerta)."""
    pos = p[p["positiva"]]
    if escenarios is not None:
        pos = pos[pos["escenario"].isin(escenarios)]
    neg = p[~p["positiva"]]
    if principales is not None:
        neg = neg[neg["principal"].isin(principales)]
    return int(pos[marca].sum()), int((~pos[marca]).sum()), int(neg[marca].sum())


def desempeno_por_grupo(p, marca):
    """Filas de la Tabla 35 (VP, FN, FP, precisión, exhaustividad, F1) para una repetición."""
    filas = {}
    for nombre, escenarios in GRUPOS_DETECCION:
        principales = p.loc[p["positiva"] & p["escenario"].isin(escenarios), "principal"].unique()
        vp, fn, fp = conteos(p, marca, principales, escenarios)
        filas[nombre] = (vp, fn, fp) + razones(vp, fp, fn)
    vp, fn, fp = conteos(p, marca)
    filas["Global"] = (vp, fn, fp) + razones(vp, fp, fn)
    return filas


def horas_legitimas(p):
    """Horas de reloj con ventanas negativas (con bosque listo)."""
    return p.loc[~p["positiva"], "inicio"].nunique() / 60.0


def elegir_umbral(curva):
    """Umbral de mayor F1; a igualdad, el de menos falsos positivos por hora."""
    if curva.empty:
        return NAN, NAN
    mejor = curva.sort_values(["f1", "fp_h"], ascending=[False, True]).iloc[0]
    return float(mejor["umbral"]), float(mejor["f1"])


def figura_umbral(curva, umbral, ruta):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    figura, eje = plt.subplots(figsize=(7, 4))
    for columna, nombre in [("precision", "Precisión"), ("exhaustividad", "Exhaustividad"), ("f1", "F1")]:
        eje.plot(curva["umbral"], curva[columna], label=nombre)
    eje.axvline(umbral, color="gray", linestyle=":", label="Umbral elegido")
    eje.set_xlabel("Umbral sobre el puntaje RCF")
    eje.set_ylabel("Valor de la métrica")
    eje_fp = eje.twinx()
    eje_fp.plot(curva["umbral"], curva["fp_h"], "k--", label="FP/h")
    eje_fp.set_ylabel("Falsos positivos por hora")
    figura.legend(loc="upper center", ncol=5)
    figura.tight_layout()
    figura.savefig(ruta, dpi=200)
    plt.close(figura)


# ------------------------------------------------------------------ cálculo global
def repeticiones(carpeta):
    return sorted((d for d in glob.glob(os.path.join(carpeta, "rep*")) if os.path.isdir(d)),
                  key=lambda d: int(re.sub(r"\D", "", os.path.basename(d)) or 0))


def limites_repeticion(carpetas):
    """(desde, hasta) de cada repetición a partir de su inicio.txt; la última llega hasta el final."""
    inicios = []
    for c in carpetas:
        ruta = os.path.join(c, "inicio.txt")
        inicios.append(pd.Timestamp(open(ruta).read().strip()) if os.path.exists(ruta) else None)
    limites = []
    for i, ini in enumerate(inicios):
        siguiente = next((x for x in inicios[i + 1:] if x is not None), None)
        limites.append((ini, siguiente if siguiente is not None else pd.Timestamp.max.tz_localize("UTC")))
    return limites


def calcular(carpeta):
    reps = repeticiones(carpeta)
    res = {"carpeta": carpeta, "repeticiones": len(reps)}

    # ----- nivel 1
    bit_rep = [leer_bitacoras(c) for c in reps]
    res["bloqueo"] = {}
    for nombre, escenarios in GRUPOS_BLOQUEO:
        estrictas, formula = [], []
        for b in bit_rep:
            e, f = tasas_bloqueo(b, escenarios)
            estrictas.append(e)
            formula.append(f)
        res["bloqueo"][nombre] = {"estricta": resumen(estrictas), "formula": resumen(formula)}
    todos = [b for b in bit_rep if not b.empty]
    bit_todas = pd.concat(todos, ignore_index=True) if todos else leer_bitacoras(carpeta)
    ataca = bit_todas[bit_todas["esperado"] == "denegado"] if not bit_todas.empty else bit_todas
    res["bloqueo_global"] = (100.0 * ataca["observado"].map(bloqueado_estricto).mean()
                             if len(ataca) else NAN)

    rutas = {c: os.path.join(carpeta, c, f"alcance_{c}.txt") for c in ("c0", "c1")}
    if all(os.path.exists(r) for r in rutas.values()):
        res["alcance"] = tabla_alcance(leer_alcance(rutas["c0"]), leer_alcance(rutas["c1"]))
    ruta_cob = os.path.join(carpeta, "cobertura.json")
    res["cobertura"] = cobertura(json.load(open(ruta_cob)) if os.path.exists(ruta_cob) else None, bit_todas)

    alertas = leer_alertas(reps)
    tta = {e: [] for e in ESCENARIOS_TTA}
    for b in bit_rep:
        t = tta_por_corrida(alertas, intervalos_ataque(b))
        for e in ESCENARIOS_TTA:
            v = t.loc[t["escenario"] == e, "tta"]
            tta[e].append(float(v.iloc[0]) if len(v) else NAN)
    res["tta"] = tta

    if all(os.path.exists(os.path.join(carpeta, c, f"latencia_portal_{c}.jsonl")) for c in ("c0", "c1")):
        res["sobrecarga"] = {c: tabla_sobrecarga(carpeta, c) for c in ("c0", "c1")}

    # ----- nivel 2
    puntajes = leer_puntajes(reps)
    if not puntajes.empty and not bit_todas.empty:
        etiquetadas = ventanas_listas(etiquetar_ventanas(puntajes, bit_todas))
        horas = horas_legitimas(etiquetadas)
        curva = umbral_mod.curva_sensibilidad(etiquetadas, horas)
        valor_umbral, f1_umbral = elegir_umbral(curva)
        etiquetadas = etiquetadas.assign(sobre_umbral=etiquetadas["puntaje"] > valor_umbral,
                                         alerta=etiquetadas["grado"] > 0)
        res.update({"curva": curva, "umbral": valor_umbral, "f1_umbral": f1_umbral,
                    "horas_legitimas": horas, "ventanas": len(etiquetadas)})
        limites = limites_repeticion(reps)
        por_rep = {"umbral": [], "alerta": []}
        fp_h = {"umbral": [], "alerta": []}
        for (desde, hasta) in limites:
            if desde is None:
                continue
            sub = etiquetadas[(etiquetadas["inicio"] >= desde) & (etiquetadas["inicio"] < hasta)]
            for marca, clave in (("sobre_umbral", "umbral"), ("alerta", "alerta")):
                por_rep[clave].append(desempeno_por_grupo(sub, marca))
                h = horas_legitimas(sub)
                fp_h[clave].append(conteos(sub, marca)[2] / h if h else NAN)
        res["desempeno"] = por_rep
        res["fp_h_por_rep"] = fp_h
        res["fp_h_total"] = {
            "umbral": conteos(etiquetadas, "sobre_umbral")[2] / horas if horas else NAN,
            "alerta": conteos(etiquetadas, "alerta")[2] / horas if horas else NAN}

    # ----- operación del servicio (última repetición: contadores acumulados de la sesión)
    ultima = reps[-1] if reps else None
    if ultima and os.path.exists(os.path.join(ultima, "metricas_auditoria.prom")):
        prom = open(os.path.join(ultima, "metricas_auditoria.prom"), encoding="utf-8").read()
        cq = lambda m, q: cuantil_histograma(prom, m, q) * 1000.0
        res["operacion"] = {
            "puntuacion_p50_ms": cq("zt_puntuacion_latencia_seconds", 0.50),
            "puntuacion_p99_ms": cq("zt_puntuacion_latencia_seconds", 0.99),
            "proceso_p99_ms": cq("zt_procesamiento_evento_seconds", 0.99),
            "muestreadas": contador(prom, "zt_ingesta_muestreadas_total")}
    return res


# ------------------------------------------------------------------ redacción
def f(x, dec=1):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "n/d"
    return f"{x:.{dec}f}".replace(".", ",")


def ms(r, dec=1):
    """media ± d. e. de un resumen()."""
    if r["n"] == 0:
        return "n/d"
    return f"{f(r['media'], dec)} ± {f(r['de'], dec)}"


def mediana(valores):
    v = [x for x in valores if not (isinstance(x, float) and math.isnan(x))]
    return float(np.median(v)) if v else NAN


def redactar(r):
    L = []
    n = r["repeticiones"]
    L.append(f"# Resultados del OE4 ({n} repeticiones)\n")
    L.append("Valores medidos; media ± desviación estándar entre repeticiones salvo que se indique.\n")

    L.append("## Tabla 32. Métricas de mitigación (nivel 1)\n")
    L.append("| Métrica | Meta | Resultado (media ± d. e.) | Observación |")
    L.append("| --- | --- | --- | --- |")
    nombres = {"E1 y E2": "E1 y E2 (violaciones de política de autenticación)",
               "E3": "E3 (evasión de VPC Lattice)", "E4": "E4 (servicio de metadatos)",
               "E5": "E5 (suplantación ante pagos)", "E6 y E8": "E6 y E8"}
    for nombre, d in r["bloqueo"].items():
        obs = ""
        if d["formula"]["n"] and abs(d["formula"]["media"] - d["estricta"]["media"]) > 1e-9:
            obs = f"Con la fórmula de metricas.py (cuenta todo 4xx): {ms(d['formula'])} %"
        L.append(f"| Tasa de bloqueo, {nombres[nombre]} | 100 % | {ms(d['estricta'])} % | {obs} |")
    if "alcance" in r:
        _, t0, t1 = r["alcance"]
        L.append(f"| Alcance lateral residual desde catalogo | 0 servicios | {t1} (C0: {t0}) | Ver Tabla 33 |")
    medianas = {e: mediana(r["tta"][e]) for e in ESCENARIOS_TTA}
    texto_tta = " / ".join(f(medianas[e], 0) for e in ESCENARIOS_TTA)
    detalle = "; ".join(f"{e}: " + ", ".join(f(x, 0) for x in r["tta"][e]) for e in ESCENARIOS_TTA)
    L.append(f"| TTA mediano, E1 / E3 / E6 / E7 / E8 (s) | Cota inferior ≈ 120 s | {texto_tta} | "
             f"Por repetición — {detalle} (n/d: sin alerta) |")
    cp, texto_cp = r["cobertura"]
    L.append(f"| Cobertura de política | 100 % | {f(cp, 0)} % | {texto_cp} |")
    L.append(f"\nTasa de bloqueo global (todos los intentos que violan una política): {f(r['bloqueo_global'])} %.\n")

    if "alcance" in r:
        filas, t0, t1 = r["alcance"]
        L.append("## Tabla 33. Alcance lateral desde el pod comprometido (catalogo)\n")
        L.append("| Destino | C0: alcanzable | C1: alcanzable | Mecanismo que bloquea en C1 |")
        L.append("| --- | --- | --- | --- |")
        for destino, a0, a1, mec in filas:
            L.append(f"| {destino} | {'sí' if a0 else 'no'} | {'sí' if a1 else 'no'} | {mec} |")
        L.append(f"| **Total de servicios alcanzables** | {t0} | {t1} | — |\n")

    if "sobrecarga" in r:
        s0, s1 = r["sobrecarga"]["c0"], r["sobrecarga"]["c1"]
        L.append("## Tabla 34. Sobrecarga introducida por el modelo\n")
        L.append("| Configuración | p50 (ms) | p95 (ms) | p99 (ms) | CPU por nodo (%) | Memoria por nodo (%) |")
        L.append("| --- | --- | --- | --- | --- | --- |")
        for nombre, s in (("C0 (línea base)", s0), ("C1 (modelo)", s1)):
            L.append(f"| {nombre} | {f(s['p50'])} | {f(s['p95'])} | {f(s['p99'])} | {f(s['cpu'])} | {f(s['mem'])} |")
        d = {k: s1[k] - s0[k] for k in ("p50", "p95", "p99", "cpu", "mem")}
        L.append(f"| **Diferencia C1 − C0** | {f(d['p50'])} | {f(d['p95'])} | {f(d['p99'])} | {f(d['cpu'])} | {f(d['mem'])} |")
        L.append(f"\nSolicitudes medidas: C0 {s0['n']}, C1 {s1['n']}; errores de transporte: C0 {s0['errores']}, C1 {s1['errores']}. "
                 "Latencia en el cliente (portal), sobre muestras individuales.\n")
        L.append("Por acción (p50 / p95, ms):\n")
        L.append("| Acción | C0 | C1 |\n| --- | --- | --- |")
        for accion in sorted(set(s0["por_accion"]) | set(s1["por_accion"])):
            a0, a1 = s0["por_accion"].get(accion, {}), s1["por_accion"].get(accion, {})
            L.append(f"| {accion} | {f(a0.get(50, NAN))} / {f(a0.get(95, NAN))} | {f(a1.get(50, NAN))} / {f(a1.get(95, NAN))} |")
        L.append("")

    if "desempeno" in r:
        L.append("## Tabla 35. Desempeño de la detección por escenario (nivel 2)\n")
        L.append(f"Umbral de puntaje elegido: {f(r['umbral'], 3)} (F1 de la curva: {f(r['f1_umbral'], 3)}); "
                 f"{r['ventanas']} ventanas con bosque listo y {f(r['horas_legitimas'], 2)} h de tráfico legítimo.\n")
        for clave, titulo in (("umbral", "Ventana marcada si el puntaje supera el umbral elegido"),
                              ("alerta", "Punto de operación por defecto del servicio (grado > 0)")):
            L.append(f"### {titulo}\n")
            L.append("| Escenario | VP | FN | FP | Precisión | Exhaust. | F1 |")
            L.append("| --- | --- | --- | --- | --- | --- | --- |")
            reps = r["desempeno"][clave]
            for nombre in [g[0] for g in GRUPOS_DETECCION] + ["Global"]:
                col = [rep[nombre] for rep in reps]
                celdas = [ms(resumen([c[i] for c in col]), 1 if i < 3 else 2) for i in range(6)]
                L.append(f"| {nombre} | " + " | ".join(celdas) + " |")
            L.append("")
        L.append("Detalle por repetición (VP / FN / FP; punto de operación por defecto en la segunda línea):\n")
        L.append("| Escenario | " + " | ".join(f"Rep. {i + 1}" for i in range(len(r['desempeno']['umbral']))) + " |")
        L.append("| --- | " + " | ".join("---" for _ in r["desempeno"]["umbral"]) + " |")
        for nombre in [g[0] for g in GRUPOS_DETECCION] + ["Global"]:
            a = " | ".join("/".join(str(x) for x in rep[nombre][:3]) for rep in r["desempeno"]["umbral"])
            b = " | ".join("/".join(str(x) for x in rep[nombre][:3]) for rep in r["desempeno"]["alerta"])
            L.append(f"| {nombre} (umbral) | {a} |")
            L.append(f"| {nombre} (grado > 0) | {b} |")
        L.append("")

    L.append("## Tabla 36. Métricas de operación del algoritmo\n")
    L.append("| Métrica | Resultado | Referencia |")
    L.append("| --- | --- | --- |")
    if "fp_h_total" in r:
        L.append(f"| Falsos positivos por hora de tráfico legítimo | {f(r['fp_h_total']['umbral'], 2)} con el umbral elegido; "
                 f"{f(r['fp_h_total']['alerta'], 2)} con grado > 0 | Criterio de operabilidad |")
    op = r.get("operacion")
    if op:
        L.append(f"| Latencia de puntuación por vector, p50 / p99 | {f(op['puntuacion_p50_ms'], 3)} / {f(op['puntuacion_p99_ms'], 3)} ms | Objetivo de diseño: p99 < 10 ms |")
        L.append(f"| Latencia interna de procesamiento por evento, p99 | {f(op['proceso_p99_ms'], 3)} ms | Objetivo de diseño: < 100 ms |")
    if "umbral" in r:
        L.append(f"| Umbral de puntaje elegido y F1 asociado | {f(r['umbral'], 3)} y {f(r['f1_umbral'], 3)} | Curva de la Figura 9 |")
    if op:
        L.append(f"| Actualizaciones de Live Tail muestreadas | {f(op['muestreadas'], 0)} | Debe ser 0 para no sesgar el vector |")
    L.append("\nLas latencias de puntuación y de procesamiento se estiman por interpolación en los cubos del "
             "histograma de Prometheus (Micrometer), de modo que son aproximadas al ancho del cubo.\n")
    return "\n".join(L)


def resumen_json(r):
    out = {"repeticiones": r["repeticiones"], "bloqueo_global": r["bloqueo_global"],
           "tta_mediano": {e: mediana(v) for e, v in r["tta"].items()}}
    if "alcance" in r:
        out["alcance_c0"], out["alcance_c1"] = r["alcance"][1], r["alcance"][2]
    out["cobertura"] = r["cobertura"][0]
    if "sobrecarga" in r:
        out["delta_p95_ms"] = r["sobrecarga"]["c1"]["p95"] - r["sobrecarga"]["c0"]["p95"]
    if "umbral" in r:
        out.update({"umbral": r["umbral"], "f1_umbral": r["f1_umbral"], "fp_h": r["fp_h_total"]})
        glob_umbral = [rep["Global"] for rep in r["desempeno"]["umbral"]]
        out["f1_global_media"] = resumen([g[5] for g in glob_umbral])["media"]
    return out


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    carpeta = argv[1]
    salida = argv[2] if len(argv) > 2 else carpeta
    os.makedirs(salida, exist_ok=True)
    r = calcular(carpeta)
    with open(os.path.join(salida, "resultados.md"), "w", encoding="utf-8") as fh:
        fh.write(redactar(r))
    with open(os.path.join(salida, "resumen.json"), "w", encoding="utf-8") as fh:
        json.dump(resumen_json(r), fh, indent=2, default=float)
    if "curva" in r:
        r["curva"].to_csv(os.path.join(salida, "sensibilidad.csv"), index=False)
        figura_umbral(r["curva"], r["umbral"], os.path.join(salida, "fig_umbral.png"))
    print(f"Escrito en {salida}")


if __name__ == "__main__":
    main(sys.argv)
