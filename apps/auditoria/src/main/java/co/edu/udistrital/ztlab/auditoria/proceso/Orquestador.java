package co.edu.udistrital.ztlab.auditoria.proceso;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import co.edu.udistrital.ztlab.auditoria.ingesta.FuenteTelemetria;
import co.edu.udistrital.ztlab.auditoria.modelo.EventoAccesoLattice;
import co.edu.udistrital.ztlab.auditoria.modelo.EventoRed;
import co.edu.udistrital.ztlab.auditoria.modelo.Puntuacion;
import co.edu.udistrital.ztlab.auditoria.modelo.ResumenVentana;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Conecta las etapas de la canalización ({fig:pipeline}). La ingesta y el cierre de
 * ventanas corren en UN solo hilo (el bosque no admite actualizaciones concurrentes):
 * ambas suscripciones publican en el mismo Scheduler de un hilo, que serializa el
 * acceso al agregador y a los bosques.
 */
@Component
public class Orquestador {

  private final FuenteTelemetria fuente;
  private final Normalizador normalizador;
  private final Agregador agregador;
  private final Extractor extractor;
  private final DetectorRcf detector;
  private final PublicadorAlertas publicador;
  private final TopologiaRegistro topologia;
  private final Metricas metricas;

  private final Scheduler hilo = Schedulers.newSingle("rcf");
  private Disposable ingesta;
  private Disposable cierre;

  public Orquestador(FuenteTelemetria fuente, Normalizador normalizador, Agregador agregador,
                     Extractor extractor, DetectorRcf detector, PublicadorAlertas publicador,
                     TopologiaRegistro topologia, Metricas metricas) {
    this.fuente = fuente;
    this.normalizador = normalizador;
    this.agregador = agregador;
    this.extractor = extractor;
    this.detector = detector;
    this.publicador = publicador;
    this.topologia = topologia;
    this.metricas = metricas;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void iniciar() {
    ingesta = fuente.flujo()
        .doOnNext(m -> metricas.contarEvento())
        .publishOn(hilo)
        .doOnNext(m -> metricas.procesamientoTimer().record(() -> ingerir(m)))
        .subscribe();

    cierre = Flux.interval(Duration.ofSeconds(10))
        .publishOn(hilo)
        .doOnNext(t -> cerrarVentanas())
        .subscribe();
  }

  private void ingerir(String mensaje) {
    try {
      EventoAccesoLattice lat = normalizador.tiparLattice(mensaje);
      metricas.registrarAcceso(lat);
      EventoRed e = normalizador.aEventoRed(lat);
      topologia.registrarArista(e);
      if (!agregador.agregar(e)) {
        metricas.contarTardio();
      }
    } catch (Exception noEsLattice) {
      // otros formatos de registro se ignoran en esta versión
    }
  }

  private void cerrarVentanas() {
    for (ResumenVentana v : agregador.cerrarHasta(Instant.now())) {
      Puntuacion p = detector.evaluar(v.principal(), v.inicio(), extractor.vector(v));
      publicador.publicar(p);
    }
  }

  @PreDestroy
  public void detener() {
    if (ingesta != null) {
      ingesta.dispose();
    }
    if (cierre != null) {
      cierre.dispose();
    }
    hilo.dispose();
  }
}
