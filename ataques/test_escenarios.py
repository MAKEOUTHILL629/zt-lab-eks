import escenarios


def test_plan_determinista():
    assert escenarios.plan(7, 5) == escenarios.plan(7, 5)
    assert escenarios.plan(7, 5) != escenarios.plan(8, 5)
    assert len(escenarios.plan(1, 10)) == 10


def test_destinos_desde_entorno():
    env = {"CATALOGO_URL": "https://c", "CLIENTES_URL": "https://cl",
           "PEDIDOS_URL": "https://p", "PAGOS_URL": "https://pg"}
    d = escenarios.destinos_desde_entorno(env)
    assert d["catalogo"] == "https://c" and d["pagos"] == "https://pg"


def test_linea_bitacora_tiene_campos():
    r = escenarios.linea_bitacora("E6-x", "E6", "clientes", "/clientes/1", "denegado", 403)
    for campo in ("ts", "corrida", "escenario", "principal", "destino", "ruta", "esperado", "observado"):
        assert campo in r
