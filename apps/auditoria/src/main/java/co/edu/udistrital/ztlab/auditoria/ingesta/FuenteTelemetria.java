package co.edu.udistrital.ztlab.auditoria.ingesta;

import reactor.core.publisher.Flux;

/** Fuente de registros crudos (RF1). Cada implementación produce un Flux de mensajes. */
public interface FuenteTelemetria {
  Flux<String> flujo();
}
