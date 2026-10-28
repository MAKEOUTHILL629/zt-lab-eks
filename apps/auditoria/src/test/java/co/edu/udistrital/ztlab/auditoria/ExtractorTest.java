package co.edu.udistrital.ztlab.auditoria;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import co.edu.udistrital.ztlab.auditoria.modelo.ResumenVentana;
import co.edu.udistrital.ztlab.auditoria.proceso.Extractor;

/** P5: cálculo del vector (x1 intensidad, x2 amplitud, x3 rechazo). */
class ExtractorTest {

  private final Extractor ex = new Extractor();

  @Test
  void p5_calculaVectorDeReferencia() {
    double[] v = ex.vector(new ResumenVentana("p", Instant.EPOCH, 4, 3, 1));
    assertEquals(Math.log1p(4), v[0], 1e-9); // intensidad
    assertEquals(3.0, v[1], 1e-9); // amplitud
    assertEquals(0.25, v[2], 1e-9); // rechazo = 1/4
  }

  @Test
  void p5_ventanaVaciaRechazoCero() {
    double[] z = ex.vector(new ResumenVentana("p", Instant.EPOCH, 0, 0, 0));
    assertEquals(0.0, z[0], 1e-9);
    assertEquals(0.0, z[1], 1e-9);
    assertEquals(0.0, z[2], 1e-9); // sin intentos, rechazo = 0 (no división por cero)
  }
}
