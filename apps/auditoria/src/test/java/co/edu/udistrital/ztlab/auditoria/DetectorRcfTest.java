package co.edu.udistrital.ztlab.auditoria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import co.edu.udistrital.ztlab.auditoria.proceso.DetectorRcf;
import co.edu.udistrital.ztlab.auditoria.proceso.Metricas;

/** P6: tras el calentamiento con una serie estable, un cambio sostenido de nivel
 *  produce un grado de anomalía mayor que cero. */
class DetectorRcfTest {

  @Test
  void p6_cambioSostenidoProduceGrado() {
    Metricas m = new Metricas(new SimpleMeterRegistry());
    DetectorRcf det = new DetectorRcf(m);
    Instant t = Instant.EPOCH;

    // Calentamiento: serie estable (baja intensidad, un destino, sin rechazo).
    for (int i = 0; i < 400; i++) {
      det.evaluar("p", t.plusSeconds(i * 60L), new double[] {Math.log1p(1), 1.0, 0.0});
    }
    // Cambio sostenido de nivel: intensidad, amplitud y rechazo altos.
    double maxGrado = 0.0;
    for (int i = 400; i < 440; i++) {
      double g = det.evaluar("p", t.plusSeconds(i * 60L),
          new double[] {Math.log1p(500), 40.0, 1.0}).grado();
      maxGrado = Math.max(maxGrado, g);
    }
    assertTrue(maxGrado > 0.0, "se esperaba grado > 0 tras el cambio sostenido; fue " + maxGrado);
  }
}
