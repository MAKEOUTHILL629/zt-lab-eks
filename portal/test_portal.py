import portal


def test_elegir_accion_mezcla():
    assert portal.elegir_accion(0.0) == "ver_catalogo"
    assert portal.elegir_accion(0.59) == "ver_catalogo"
    assert portal.elegir_accion(0.60) == "crear_pedido"
    assert portal.elegir_accion(0.89) == "crear_pedido"
    assert portal.elegir_accion(0.90) == "ver_pedido"
    assert portal.elegir_accion(0.999) == "ver_pedido"
