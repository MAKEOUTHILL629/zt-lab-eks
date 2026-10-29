import json
import math

import pandas as pd

import resultados as r

BASE = pd.Timestamp("2026-01-01T00:00:00Z")


def iso(minutos, segundos=0):
    return (BASE + pd.Timedelta(minutes=minutos, seconds=segundos)).isoformat()


def escribir_jsonl(ruta, filas):
    ruta.write_text("\n".join(json.dumps(f) for f in filas) + "\n", encoding="utf-8")


def registro(escenario, principal, minuto, segundo, esperado, observado, corrida=None):
    return {"ts": iso(minuto, segundo), "corrida": corrida or f"{escenario}-1", "escenario": escenario,
            "principal": principal, "destino": "x", "ruta": "/", "esperado": esperado,
            "observado": observado}


def crear_repeticion(carpeta, desplazamiento):
    """Una sesión de 120 minutos: los bosques empiezan a puntuar en el minuto 10."""
    carpeta.mkdir()
    d = desplazamiento
    escribir_jsonl(carpeta / "bitacora_E1.jsonl", [
        registro("E1", "tienda/catalogo", d + 60, 10, "denegado", 403, "E1-a"),
        registro("E1", "tienda/catalogo", d + 60, 12, "denegado", 403, "E1-a")])
    escribir_jsonl(carpeta / "bitacora_E3.jsonl", [
        registro("E3", "tienda/catalogo", d + 70, 5, "denegado", "TimeoutError", "E3-a"),
        registro("E3", "tienda/catalogo", d + 70, 8, "denegado", "ConnectionRefusedError", "E3-a")])
    escribir_jsonl(carpeta / "bitacora_E5.jsonl", [
        registro("E5", "tienda/catalogo", d + 75, 0, "denegado", "SSLError", "E5-a")])
    escribir_jsonl(carpeta / "bitacora_E6.jsonl", [
        registro("E6", "tienda/catalogo", d + 80 + i, 20, "denegado", 403 if i % 5 else 404, "E6-a")
        for i in range(10)])
    escribir_jsonl(carpeta / "bitacora_E7.jsonl", [
        registro("E7", "tienda/pedidos", d + 95 + i, 15, "permitido", 200, "E7-a") for i in range(10)])
    filas = []
    for principal in ("tienda/portal", "tienda/pedidos", "tienda/catalogo"):
        for m in range(d, d + 120):
            ataque = (principal == "tienda/catalogo" and (60 <= m - d <= 60 or 80 <= m - d < 90)) or (
                principal == "tienda/pedidos" and 95 <= m - d < 105)
            puntaje = 0.0 if m - d < 10 else (3.0 if ataque else 0.5 + 0.01 * (m % 7))
            if principal == "tienda/portal" and m - d == 100:
                puntaje = 3.0  # un falso positivo, dentro de la repetición (inicia en d + 55)
            filas.append((principal, iso(m), puntaje, 1.0 if puntaje >= 3.0 else 0.0))
    pd.DataFrame(filas, columns=["principal", "inicio", "puntaje", "grado"]).to_csv(
        carpeta / "puntajes.csv", index=False)
    (carpeta / "alertas.json").write_text(json.dumps([
        {"principal": "tienda/catalogo", "inicio": iso(d + 60), "puntaje": 3.0, "grado": 1.0, "tta": 250},
        {"principal": "tienda/portal", "inicio": iso(d + 100), "puntaje": 3.0, "grado": 1.0, "tta": 240}]))
    (carpeta / "estado.json").write_text(json.dumps({"eventos": 1000, "tardios": 0, "muestreadas": 0}))
    (carpeta / "metricas_auditoria.prom").write_text(
        'zt_puntuacion_latencia_seconds_bucket{le="0.001"} 50\n'
        'zt_puntuacion_latencia_seconds_bucket{le="0.002"} 100\n'
        'zt_puntuacion_latencia_seconds_bucket{le="+Inf"} 100\n'
        'zt_procesamiento_evento_seconds_bucket{le="0.01"} 100\n'
        'zt_procesamiento_evento_seconds_bucket{le="+Inf"} 100\n'
        'zt_ingesta_muestreadas_total 0.0\n')
    (carpeta / "inicio.txt").write_text(iso(d + 55))


def crear_configuracion(carpeta, cfg, factor, alcanzables):
    (carpeta / cfg).mkdir()
    escribir_jsonl(carpeta / cfg / f"latencia_portal_{cfg}.jsonl", [
        {"ts": 1.0, "modo": cfg, "accion": "ver_catalogo", "ms": float(i % 20 + 1) * factor, "estado": "ok"}
        for i in range(200)])
    (carpeta / cfg / f"consumo_{cfg}.json").write_text(json.dumps(
        {"consumo": {"cpu": {"a": 10.0 * factor, "b": 12.0 * factor}, "mem": {"a": 20.0, "b": 22.0}}}))
    (carpeta / cfg / f"alcance_{cfg}.txt").write_text(
        json.dumps({"clientes": alcanzables, "pedidos": alcanzables, "pagos": alcanzables,
                    "ip_clientes": alcanzables, "ip_pagos": alcanzables, "imds": False}, indent=2)
        + "\nservicios alcanzables: 3\n")


def datos(tmp_path):
    carpeta = tmp_path / "datos"
    carpeta.mkdir()
    crear_configuracion(carpeta, "c0", 1.0, True)
    crear_configuracion(carpeta, "c1", 3.0, False)
    crear_repeticion(carpeta / "rep1", 0)
    crear_repeticion(carpeta / "rep2", 130)
    (carpeta / "cobertura.json").write_text(json.dumps(
        {"catalogo": True, "clientes": True, "pedidos": True, "pagos_ruta_tls": True}))
    return carpeta


def test_cuantil_histograma_interpola_en_el_cubo():
    texto = ('x_seconds_bucket{le="0.001"} 50\nx_seconds_bucket{le="0.002"} 100\n'
             'x_seconds_bucket{le="+Inf"} 100\n')
    assert math.isclose(r.cuantil_histograma(texto, "x_seconds", 0.5), 0.001)
    assert math.isclose(r.cuantil_histograma(texto, "x_seconds", 0.75), 0.0015)
    assert math.isnan(r.cuantil_histograma("", "x_seconds", 0.5))


def test_cubo_cuantil_devuelve_los_limites_del_cubo():
    texto = ('x_seconds_bucket{le="0.001"} 50\nx_seconds_bucket{le="0.002"} 100\n'
             'x_seconds_bucket{le="+Inf"} 100\n')
    assert r.cubo_cuantil(texto, "x_seconds", 0.5) == (0.0, 0.001)
    assert r.cubo_cuantil(texto, "x_seconds", 0.99) == (0.001, 0.002)
    assert all(math.isnan(v) for v in r.cubo_cuantil("", "x_seconds", 0.5))


def test_bloqueo_estricto_no_cuenta_un_404():
    assert r.bloqueado_estricto(403) and r.bloqueado_estricto("SSLError")
    assert not r.bloqueado_estricto(404) and not r.bloqueado_estricto("conectado")
    assert not r.bloqueado_estricto(200)


def test_percentiles_y_resumen():
    assert r.percentiles(list(range(1, 101)), (50,))[50] == 50.5
    s = r.resumen([1.0, 3.0, float("nan")])
    assert s["n"] == 2 and s["media"] == 2.0 and s["min"] == 1.0 and s["max"] == 3.0


def test_ventanas_listas_descarta_el_calentamiento():
    p = pd.DataFrame({"principal": ["a"] * 4, "inicio": pd.to_datetime([iso(i) for i in range(4)], utc=True),
                      "puntaje": [0.0, 0.0, 0.7, 0.6]})
    assert len(r.ventanas_listas(p)) == 2


def test_calcular_con_datos_sinteticos(tmp_path):
    res = r.calcular(str(datos(tmp_path)))
    assert res["repeticiones"] == 2
    assert res["bloqueo"]["E1 y E2"]["estricta"]["media"] == 100.0
    assert res["bloqueo"]["E3"]["estricta"]["media"] == 100.0
    assert res["bloqueo"]["E5"]["estricta"]["media"] == 100.0
    # E6 incluye un 404 de cada cinco: la fórmula antigua lo cuenta como bloqueo y la estricta no
    assert res["bloqueo"]["E6 y E8"]["estricta"]["media"] == 80.0
    assert res["bloqueo"]["E6 y E8"]["formula"]["media"] == 100.0
    filas, t0, t1 = res["alcance"]
    assert (t0, t1) == (3, 0)
    assert res["cobertura"][0] == 100.0
    # alerta de catalogo con inicio en el minuto 60 y TTA de 250 s; primer intento en 60:10
    assert res["tta"]["E1"][0] == 250 - 10
    assert math.isnan(res["tta"]["E3"][0])  # E3 no pasa por Lattice: no hay alerta
    assert res["sobrecarga"]["c1"]["p50"] > res["sobrecarga"]["c0"]["p50"]
    glob = res["desempeno"]["umbral"][0]["Global"]
    assert glob[0] > 0 and glob[3] > 0.5 and glob[5] > 0.5  # VP, precisión y F1
    assert res["fp_h_total"]["umbral"] > 0  # el falso positivo del portal
    assert res["operacion"]["muestreadas"] == 0.0
    assert math.isclose(res["operacion"]["puntuacion_p99_ms"], 1.98, rel_tol=1e-6)
    assert res["operacion"]["puntuacion_p99_cubo"] == (1.0, 2.0)  # milisegundos


def test_el_calentamiento_del_protocolo_no_entra_en_la_curva_ni_en_fp_h(tmp_path):
    carpeta = datos(tmp_path)
    base = r.calcular(str(carpeta))
    ruta = carpeta / "rep1" / "puntajes.csv"
    p = pd.read_csv(ruta)
    # una ventana benigna de alto puntaje con bosque listo (minuto 20) pero antes de iniciar la repetición 1 (minuto 55)
    p.loc[(p["principal"] == "tienda/portal") & (p["inicio"] == iso(20)), "puntaje"] = 3.0
    p.to_csv(ruta, index=False)
    con_extra = r.calcular(str(carpeta))
    assert con_extra["ventanas"] == base["ventanas"]
    assert con_extra["fp_h_total"] == base["fp_h_total"]
    assert con_extra["umbral"] == base["umbral"]


def test_main_escribe_los_archivos(tmp_path):
    carpeta = datos(tmp_path)
    salida = tmp_path / "salida"
    r.main(["resultados.py", str(carpeta), str(salida)])
    texto = (salida / "resultados.md").read_text(encoding="utf-8")
    for tabla in ("Tabla 32", "Tabla 33", "Tabla 34", "Tabla 35", "Tabla 36"):
        assert tabla in texto
    assert (salida / "fig_umbral.png").exists() and (salida / "sensibilidad.csv").exists()
    assert json.loads((salida / "resumen.json").read_text())["repeticiones"] == 2
