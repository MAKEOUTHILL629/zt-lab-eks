package co.edu.udistrital.ztlab.pedidos;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientSsl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import co.edu.udistrital.ztlab.pedidos.Dtos.Pago;
import co.edu.udistrital.ztlab.pedidos.Dtos.SolicitudPago;
import reactor.core.publisher.Mono;

/**
 * Pedidos: cliente mTLS hacia pagos que se reconstruye al rotar el certificado
 * (modo B, {lst:d_cliente}). Solo activo en el perfil c1.
 */
@Component
@Profile("c1")
public class ClientePagos implements RegistroPagos {

  private final AtomicReference<WebClient> cliente = new AtomicReference<>();
  private final WebClient.Builder constructor;
  private final WebClientSsl ssl;
  private final String baseUrl;

  public ClientePagos(WebClient.Builder constructor, WebClientSsl ssl, SslBundles paquetes,
                      @Value("${PAGOS_URL:https://pagos.zt-lab.internal:8443}") String baseUrl) {
    this.constructor = constructor;
    this.ssl = ssl;
    this.baseUrl = baseUrl;
    cliente.set(construir(paquetes.getBundle("cliente")));
    // cert-manager renueva el certificado: se reemplaza el cliente en caliente.
    paquetes.addBundleUpdateHandler("cliente", nuevo -> cliente.set(construir(nuevo)));
  }

  private WebClient construir(SslBundle paquete) {
    return constructor.clone()
        .baseUrl(baseUrl) // SNI del oyente TLS (dominio personalizado de pagos)
        .apply(ssl.fromBundle(paquete))
        .build();
  }

  @Override
  public Mono<Pago> registrar(SolicitudPago solicitud) {
    return cliente.get().post().uri("/pagos")
        .bodyValue(solicitud)
        .retrieve()
        .bodyToMono(Pago.class);
  }
}
