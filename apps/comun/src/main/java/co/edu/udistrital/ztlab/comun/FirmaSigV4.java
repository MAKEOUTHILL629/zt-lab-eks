package co.edu.udistrital.ztlab.comun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

/**
 * Firma cada solicitud del WebClient con SigV4 para VPC Lattice (modo A, {lst:d_firma}).
 * Solo se activa con el perfil c1; en la línea base c0 las llamadas van sin firmar.
 */
@Component
@Profile("c1")
public class FirmaSigV4 implements ExchangeFilterFunction {

  private final AwsV4HttpSigner firmador = AwsV4HttpSigner.create();
  // Cadena por defecto: obtiene las credenciales temporales de EKS Pod Identity.
  private final AwsCredentialsProvider credenciales = DefaultCredentialsProvider.builder().build();
  private final String region;

  public FirmaSigV4(@Value("${zt.region:us-east-1}") String region) {
    this.region = region;
  }

  @Override
  public Mono<ClientResponse> filter(ClientRequest solicitud, ExchangeFunction sig) {
    return Mono.fromCallable(() -> firmar(solicitud))
        .subscribeOn(Schedulers.boundedElastic()) // renovar credenciales puede bloquear
        .flatMap(sig::exchange);
  }

  private ClientRequest firmar(ClientRequest solicitud) {
    SdkHttpRequest aFirmar = SdkHttpRequest.builder()
        .uri(solicitud.url())
        .method(SdkHttpMethod.fromValue(solicitud.method().name()))
        .build();
    SignedRequest firmada = firmador.sign(r -> r
        .identity(credenciales.resolveCredentials())
        .request(aFirmar)
        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "vpc-lattice-svcs")
        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
        .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)); // UNSIGNED-PAYLOAD
    return ClientRequest.from(solicitud)
        .headers(cabeceras -> firmada.request().headers().forEach(cabeceras::put))
        .build();
  }
}
