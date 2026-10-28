package co.edu.udistrital.ztlab.auditoria.proceso;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import co.edu.udistrital.ztlab.auditoria.modelo.EventoAccesoLattice;
import co.edu.udistrital.ztlab.auditoria.modelo.EventoRed;

/**
 * Convierte cada registro crudo en un evento tipado y resuelve la identidad del origen
 * (RF2, sección 7.3.4). Las etiquetas de sesión dan la identidad en los registros
 * autenticados; en los demás se usa la tabla IP -> identidad.
 */
@Component
public class Normalizador {

  private final ObjectMapper mapper = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .build();
  private final ResolverIdentidad resolver;

  public Normalizador(ResolverIdentidad resolver) {
    this.resolver = resolver;
  }

  /** Tipa un registro de acceso de VPC Lattice (P1). */
  public EventoAccesoLattice tiparLattice(String json) throws Exception {
    return mapper.readValue(json, EventoAccesoLattice.class);
  }

  /** Resuelve la identidad y produce el evento normalizado común (P1, P2). */
  public EventoRed aEventoRed(EventoAccesoLattice e) {
    String principal = e.principal();
    if (principal != null) {
      resolver.aprender(e.ipOrigen(), principal); // aprendizaje pasivo
    } else {
      principal = resolver.resolver(e.ipOrigen());
    }
    String destino = (e.serviceArn() == null ? "" : servicio(e.serviceArn()))
        + (e.requestPath() == null ? "" : e.requestPath());
    return new EventoRed(e.instante(), principal, destino, e.denegado());
  }

  /** Normaliza un mensaje crudo de la canalización; vacío si no se puede tipar. */
  public Optional<EventoRed> normalizar(String mensaje) {
    try {
      return Optional.of(aEventoRed(tiparLattice(mensaje)));
    } catch (Exception ignorada) {
      return Optional.empty();
    }
  }

  private static String servicio(String arn) {
    int i = arn.lastIndexOf('/');
    return i < 0 ? arn : arn.substring(i + 1);
  }
}
