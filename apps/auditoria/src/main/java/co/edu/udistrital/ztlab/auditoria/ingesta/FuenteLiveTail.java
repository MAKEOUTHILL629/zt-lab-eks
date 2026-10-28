package co.edu.udistrital.ztlab.auditoria.ingesta;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import co.edu.udistrital.ztlab.auditoria.proceso.Metricas;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.util.retry.Retry;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LiveTailSessionLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.LiveTailSessionUpdate;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartLiveTailRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartLiveTailResponseHandler;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartLiveTailResponseStream;

/**
 * Fuente reactiva basada en CloudWatch Logs Live Tail ({lst:livetail}, sección 7.3.3).
 * Reconecta al cumplirse el límite de sesión (3 h) con repeat() y ante errores con
 * backoff. Cuenta las actualizaciones muestreadas, que sesgarían el vector.
 */
@Component
@Profile("c1")
public class FuenteLiveTail implements FuenteTelemetria {

  private final CloudWatchLogsAsyncClient cliente;
  private final List<String> gruposDeRegistros; // ARN de los tres grupos
  private final Metricas metricas;

  public FuenteLiveTail(CloudWatchLogsAsyncClient cliente, Metricas metricas,
                        @Value("${ZT_GRUPOS:}") String grupos) {
    this.cliente = cliente;
    this.metricas = metricas;
    this.gruposDeRegistros = grupos == null || grupos.isBlank()
        ? List.of() : Arrays.stream(grupos.split(",")).map(String::trim).toList();
  }

  @Override
  public Flux<String> flujo() {
    return eventos().map(LiveTailSessionLogEvent::message);
  }

  /** Reconecta al terminar una sesión (repeat) y ante errores (backoff). */
  Flux<LiveTailSessionLogEvent> eventos() {
    return sesion()
        .repeat() // fin de sesión (3 h): reabrir
        .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))); // errores
  }

  /** Una sesión de Live Tail como Flux. Reemplazable en pruebas. */
  protected Flux<LiveTailSessionLogEvent> sesion() {
    return Flux.<LiveTailSessionLogEvent>create(sumidero -> {
          StartLiveTailRequest solicitud = StartLiveTailRequest.builder()
              .logGroupIdentifiers(gruposDeRegistros).build();
          StartLiveTailResponseHandler manejador = StartLiveTailResponseHandler.builder()
              .onError(sumidero::error)
              .subscriber(() -> new Subscriber<StartLiveTailResponseStream>() {
                public void onSubscribe(Subscription s) {
                  sumidero.onDispose(s::cancel);
                  s.request(Long.MAX_VALUE);
                }

                public void onNext(StartLiveTailResponseStream evento) {
                  if (evento instanceof LiveTailSessionUpdate actualizacion) {
                    if (Boolean.TRUE.equals(actualizacion.sessionMetadata().sampled())) {
                      metricas.contarMuestreada();
                    }
                    actualizacion.sessionResults().forEach(sumidero::next);
                  }
                }

                public void onError(Throwable t) {
                  sumidero.error(t);
                }

                public void onComplete() {
                  sumidero.complete();
                }
              })
              .build();
          cliente.startLiveTail(solicitud, manejador);
        }, FluxSink.OverflowStrategy.BUFFER);
  }
}
