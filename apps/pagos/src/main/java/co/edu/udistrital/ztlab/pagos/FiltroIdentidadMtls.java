package co.edu.udistrital.ztlab.pagos;

import java.security.cert.X509Certificate;
import java.util.Set;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import reactor.core.publisher.Mono;

/**
 * Pagos: autoriza por el nombre común del certificado de cliente (modo B, {lst:d_filtro}).
 * Solo en el perfil c1; en C0 no hay mTLS. Cuenta los rechazos en zt_mtls_rechazos_total.
 */
@Component
@Profile("c1")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroIdentidadMtls implements WebFilter {

  private static final Set<String> AUTORIZADOS = Set.of("pedidos");
  private final Counter rechazos;

  public FiltroIdentidadMtls(MeterRegistry registro) {
    this.rechazos = Counter.builder("zt.mtls.rechazos")
        .description("Certificado de cliente válido con identidad no autorizada")
        .register(registro);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange intercambio, WebFilterChain cadena) {
    if (!intercambio.getRequest().getPath().value().startsWith("/pagos")) {
      return cadena.filter(intercambio);
    }
    SslInfo tls = intercambio.getRequest().getSslInfo();
    X509Certificate[] cadenaCliente = tls == null ? null : tls.getPeerCertificates();
    String cn = cadenaCliente == null || cadenaCliente.length == 0
        ? null : nombreComun(cadenaCliente[0]);
    if (cn != null && AUTORIZADOS.contains(cn)) {
      return cadena.filter(intercambio);
    }
    rechazos.increment();
    intercambio.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
    return intercambio.getResponse().setComplete();
  }

  private static String nombreComun(X509Certificate certificado) {
    try {
      String sujeto = certificado.getSubjectX500Principal().getName();
      return new LdapName(sujeto).getRdns().stream()
          .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
          .map(rdn -> rdn.getValue().toString())
          .findFirst().orElse(null);
    } catch (InvalidNameException e) {
      return null;
    }
  }
}
