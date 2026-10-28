import portal


def test_elegir_accion_mezcla():
    assert portal.elegir_accion(0.0) == "ver_catalogo"
    assert portal.elegir_accion(0.59) == "ver_catalogo"
    assert portal.elegir_accion(0.60) == "crear_pedido"
    assert portal.elegir_accion(0.89) == "crear_pedido"
    assert portal.elegir_accion(0.90) == "ver_pedido"
    assert portal.elegir_accion(0.999) == "ver_pedido"


def test_registrar_latencia_emite_json(capsys):
    import json
    portal.registrar_latencia("ver_catalogo", 12.345, "ok")
    linea = json.loads(capsys.readouterr().out)
    assert linea["accion"] == "ver_catalogo" and linea["ms"] == 12.35 and linea["estado"] == "ok"
