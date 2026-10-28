import pandas as pd
import analisis_umbral as a
import metricas as m


def _puntajes():
    # 50 ventanas legítimas (puntaje bajo) + 10 de ataque (puntaje alto), como en
    # operación real los ataques son escasos, de modo que el barrido 0.80-0.999 del
    # cuantil cruza el umbral que separa ambas poblaciones.
    filas = []
    for i in range(50):
        filas.append(("tienda/catalogo", f"2026-01-01T00:{i:02d}:00Z", 0.5, 0.0))
    for i in range(50, 60):
        filas.append(("tienda/catalogo", f"2026-01-01T00:{i:02d}:00Z", 3.0, 1.0))
    return pd.DataFrame(filas, columns=["principal", "inicio", "puntaje", "grado"])


def _bitacora():
    # Un ataque que solapa las ventanas 50..59.
    return pd.DataFrame([
        {"ts": "2026-01-01T00:50:30Z", "corrida": "E8-1", "principal": "tienda/catalogo"},
        {"ts": "2026-01-01T00:59:30Z", "corrida": "E8-1", "principal": "tienda/catalogo"},
    ])


def test_etiquetar_marca_ventanas_de_ataque():
    p = a.etiquetar(_puntajes(), _bitacora())
    assert p["positiva"].sum() >= 10
    assert not p.iloc[0]["positiva"]  # la primera ventana es legítima


def test_curva_sensibilidad_encuentra_umbral_con_f1_alto():
    p = a.etiquetar(_puntajes(), _bitacora())
    curva = a.curva_sensibilidad(p, horas_legitimas=50 / 60)
    assert curva["f1"].max() > 0.5


def test_cobertura_politica():
    assert m.cobertura_politica(4, 4) == 100.0
