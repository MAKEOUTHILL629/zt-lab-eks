package co.edu.udistrital.ztlab.auditoria.proceso;

import org.springframework.stereotype.Component;
import co.edu.udistrital.ztlab.auditoria.modelo.ResumenVentana;

/**
 * Calcula el vector tridimensional de una ventana ({tab:caracteristicas}):
 *   x1 intensidad = ln(1 + N)
 *   x2 amplitud   = número de destinos distintos
 *   x3 rechazo    = proporción de intentos denegados (0 si N = 0)
 */
@Component
public class Extractor {
  public double[] vector(ResumenVentana v) {
    double x1 = Math.log1p(v.intentos());
    double x2 = v.destinosDistintos();
    double x3 = v.intentos() == 0 ? 0.0 : (double) v.denegados() / v.intentos();
    return new double[] {x1, x2, x3};
  }
}
