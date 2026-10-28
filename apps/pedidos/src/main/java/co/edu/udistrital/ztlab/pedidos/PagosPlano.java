package co.edu.udistrital.ztlab.pedidos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import co.edu.udistrital.ztlab.pedidos.Dtos.Pago;
import co.edu.udistrital.ztlab.pedidos.Dtos.SolicitudPago;
import reactor.core.publisher.Mono;

/** Línea base C0: pedidos registra el pago por HTTP plano (sin mTLS). */
@Component
@Profile("!c1")
public class PagosPlano implements RegistroPagos {

  private final WebClient cliente;

  public PagosPlano(WebClient.Builder constructor,
                    @Value("${PAGOS_URL:http://pagos.tienda.svc.cluster.local:8080}") String baseUrl) {
    this.cliente = constructor.clone().baseUrl(baseUrl).build();
  }

  @Override
  public Mono<Pago> registrar(SolicitudPago solicitud) {
    return cliente.post().uri("/pagos").bodyValue(solicitud).retrieve().bodyToMono(Pago.class);
  }
}
