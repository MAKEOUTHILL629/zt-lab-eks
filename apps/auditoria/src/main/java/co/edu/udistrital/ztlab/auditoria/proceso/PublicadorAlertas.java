package co.edu.udistrital.ztlab.auditoria.proceso;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import co.edu.udistrital.ztlab.auditoria.modelo.Alerta;
import co.edu.udistrital.ztlab.auditoria.modelo.Puntuacion;

/**
 * Publica alertas (grado > 0), conserva el historial reciente para la API y actualiza
 * el grado de cada principal en la topología (RF6, sección 7.3.7). Ninguna alerta
 * modifica las políticas (delimitación: la capa de detección solo alerta).
 */
@Component
public class PublicadorAlertas {

  private static final Logger log = LoggerFactory.getLogger(PublicadorAlertas.class);
  private static final int MAX = 5000;

  private final ConcurrentLinkedDeque<Alerta> alertas = new ConcurrentLinkedDeque<>();
  private final ConcurrentLinkedDeque<Puntuacion> puntajes = new ConcurrentLinkedDeque<>();
  private final TopologiaRegistro topologia;

  public PublicadorAlertas(TopologiaRegistro topologia) {
    this.topologia = topologia;
  }

  public void publicar(Puntuacion p) {
    recortar(puntajes);
    puntajes.addLast(p);
    topologia.actualizarNodo(p.principal(), p.grado());
    if (p.grado() > 0.0) {
      Instant ahora = Instant.now();
      long tta = Math.max(0, ahora.getEpochSecond() - p.inicio().getEpochSecond());
      Alerta a = new Alerta(p.principal(), p.inicio(), p.vector(), p.puntaje(), p.grado(), ahora, tta);
      recortar(alertas);
      alertas.addLast(a);
      log.warn("{\"tipo\":\"alerta\",\"principal\":\"{}\",\"grado\":{},\"puntaje\":{},\"tta\":{}}",
          p.principal(), p.grado(), p.puntaje(), tta);
    }
  }

  public List<Alerta> alertas() {
    return new ArrayList<>(alertas);
  }

  public List<Puntuacion> puntajes() {
    return new ArrayList<>(puntajes);
  }

  private static void recortar(ConcurrentLinkedDeque<?> cola) {
    while (cola.size() >= MAX) {
      cola.pollFirst();
    }
  }
}
