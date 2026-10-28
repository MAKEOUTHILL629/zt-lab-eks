package co.edu.udistrital.ztlab.auditoria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.udistrital.ztlab.auditoria.modelo.EventoRed;
import co.edu.udistrital.ztlab.auditoria.modelo.ResumenVentana;
import co.edu.udistrital.ztlab.auditoria.proceso.Agregador;

/** P3 y P4: ventanas por tiempo de evento, cierre con tolerancia y vectores en cero. */
class AgregadorTest {

  private final Instant t0 = Instant.ofEpochMilli(0);

  @Test
  void p3_ventanasCierreYTardios() {
    Agregador ag = new Agregador(60_000, 180_000);
    assertTrue(ag.agregar(new EventoRed(t0.plusSeconds(10), "tienda/catalogo", "d1", false)));
    assertTrue(ag.agregar(new EventoRed(t0.plusSeconds(50), "tienda/catalogo", "d2", true)));

    // Antes de fin + tolerancia (60 + 180 = 240 s), la ventana no cierra.
    assertTrue(ag.cerrarHasta(t0.plusSeconds(120)).isEmpty());

    List<ResumenVentana> cerradas = ag.cerrarHasta(t0.plusSeconds(241));
    assertEquals(1, cerradas.size());
    ResumenVentana v = cerradas.get(0);
    assertEquals("tienda/catalogo", v.principal());
    assertEquals(2, v.intentos());
    assertEquals(2, v.destinosDistintos());
    assertEquals(1, v.denegados());

    // Un evento en una ventana ya cerrada llega tarde y se descarta.
    assertFalse(ag.agregar(new EventoRed(t0.plusSeconds(30), "tienda/catalogo", "d3", false)));
  }

  @Test
  void p4_vectorCeroParaPrincipalConocidoSinActividad() {
    Agregador ag = new Agregador(60_000, 180_000);
    // Ventana [0,60): actúan portal y catalogo.
    ag.agregar(new EventoRed(t0.plusSeconds(5), "tienda/portal", "d", false));
    ag.agregar(new EventoRed(t0.plusSeconds(5), "tienda/catalogo", "d", false));
    // Ventana [60,120): solo actúa portal.
    ag.agregar(new EventoRed(t0.plusSeconds(65), "tienda/portal", "d", false));

    List<ResumenVentana> cerradas = ag.cerrarHasta(t0.plusSeconds(305)); // cierra ambas
    boolean catalogoEnCero = cerradas.stream().anyMatch(v ->
        v.principal().equals("tienda/catalogo")
            && v.inicio().equals(t0.plusSeconds(60))
            && v.intentos() == 0);
    assertTrue(catalogoEnCero, "catalogo debe emitir vector en cero en la ventana sin actividad");
  }
}
