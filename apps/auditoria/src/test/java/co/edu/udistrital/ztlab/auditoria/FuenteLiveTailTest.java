package co.edu.udistrital.ztlab.auditoria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import co.edu.udistrital.ztlab.auditoria.ingesta.FuenteLiveTail;
import co.edu.udistrital.ztlab.auditoria.proceso.Metricas;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LiveTailSessionLogEvent;

/** P7: la fuente Live Tail reabre la sesión al terminar (repeat), con el cliente del
 *  SDK sustituido por un doble de prueba. */
class FuenteLiveTailTest {

  @Test
  void p7_reconectaTrasFinDeSesion() {
    CloudWatchLogsAsyncClient cliente = Mockito.mock(CloudWatchLogsAsyncClient.class);
    Metricas metricas = new Metricas(new SimpleMeterRegistry());
    AtomicInteger sesiones = new AtomicInteger();

    FuenteLiveTail fuente = new FuenteLiveTail(cliente, metricas, "arn:grupo") {
      @Override
      protected Flux<LiveTailSessionLogEvent> sesion() {
        sesiones.incrementAndGet();
        return Flux.just(LiveTailSessionLogEvent.builder().message("x").build());
      }
    };

    StepVerifier.create(fuente.flujo().take(3))
        .expectNext("x", "x", "x")
        .verifyComplete();

    assertTrue(sesiones.get() >= 3, "debe reabrir la sesión al terminar; fueron " + sesiones.get());
  }
}
