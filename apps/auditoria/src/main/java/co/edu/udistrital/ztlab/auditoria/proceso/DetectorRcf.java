package co.edu.udistrital.ztlab.auditoria.proceso;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.amazon.randomcutforest.config.TransformMethod;
import com.amazon.randomcutforest.parkservices.AnomalyDescriptor;
import com.amazon.randomcutforest.parkservices.ThresholdedRandomCutForest;

import co.edu.udistrital.ztlab.auditoria.modelo.Puntuacion;

/**
 * Un bosque TRCF por principal ({lst:detector}, sección 7.3.6). Con shingling interno
 * (activo por defecto en modo estándar) process() recibe el vector base de 3 dim. y el
 * bosque mantiene el contexto temporal de 8 ventanas (24 dim.). Las actualizaciones del
 * bosque no son seguras entre hilos: evaluar() se invoca siempre desde el mismo hilo.
 */
@Component
public class DetectorRcf {

  private static final int DIMENSIONES_BASE = 3;
  private static final int SHINGLE = 8;

  private final Map<String, ThresholdedRandomCutForest> bosques = new HashMap<>();
  private final Metricas metricas;

  public DetectorRcf(Metricas metricas) {
    this.metricas = metricas;
  }

  private ThresholdedRandomCutForest nuevoBosque() {
    return ThresholdedRandomCutForest.builder()
        .dimensions(DIMENSIONES_BASE * SHINGLE)
        .shingleSize(SHINGLE)
        .numberOfTrees(50)
        .sampleSize(256)
        .anomalyRate(0.01)
        .transformMethod(TransformMethod.NORMALIZE)
        .autoAdjust(false)
        .randomSeed(42L)
        .build();
  }

  /** Puntúa un vector. Invocar siempre desde el mismo hilo (Schedulers.single()). */
  public Puntuacion evaluar(String principal, Instant inicio, double[] vector) {
    ThresholdedRandomCutForest bosque = bosques.computeIfAbsent(principal, p -> nuevoBosque());
    AnomalyDescriptor d = metricas.puntuacionTimer().record(
        () -> bosque.process(vector, inicio.getEpochSecond()));
    double puntaje = d.getRCFScore();
    double grado = d.getAnomalyGrade();
    metricas.actualizarRcf(principal, puntaje, grado);
    return new Puntuacion(principal, inicio, vector, puntaje, grado);
  }
}
