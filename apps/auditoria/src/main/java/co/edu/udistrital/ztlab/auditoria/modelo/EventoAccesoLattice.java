package co.edu.udistrital.ztlab.auditoria.modelo;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Tipado del registro de acceso de VPC Lattice ({lst:evento}, sección 7.3.4). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoAccesoLattice(
    JsonNode startTime, // texto ISO-8601 (formato de VPC Lattice) o milisegundos desde época
    String sourceIpPort,
    String serviceArn,
    String requestMethod,
    String requestPath,
    int responseCode,
    String authDeniedReason, // Service, Network, Identity; "-" o null si no hubo denegación
    String failureReason, // p. ej. ClientAccessDenied en TLS passthrough; "-" si no hubo fallo
    String callerPrincipal,
    JsonNode callerPrincipalTags, // etiquetas de sesión de EKS Pod Identity
    long bytesSent,
    long bytesReceived,
    long duration) {

  public boolean denegado() {
    return presente(authDeniedReason) || presente(failureReason) || responseCode == 403;
  }

  /** Marca de tiempo del evento como Instant. */
  public Instant instante() {
    if (startTime != null && startTime.isTextual()) {
      return Instant.parse(startTime.asText());
    }
    return Instant.ofEpochMilli(startTime == null ? 0 : startTime.asLong());
  }

  /** Identidad del origen a partir de las etiquetas de sesión; null si no hay. */
  public String principal() {
    if (callerPrincipalTags == null || !callerPrincipalTags.isObject()) {
      return desdeRol(); // sin etiquetas de sesión: se usa el rol del llamante
    }
    String ns = callerPrincipalTags.path("kubernetes-namespace").asText("");
    String sa = callerPrincipalTags.path("kubernetes-service-account").asText("");
    if (ns.isEmpty() || sa.isEmpty()) {
      return desdeRol();
    }
    return ns + "/" + sa;
  }

  private static boolean presente(String valor) {
    return valor != null && !valor.isEmpty() && !valor.equals("-") && !valor.equals("null");
  }

  /**
   * Los registros reales de VPC Lattice no incluyen las etiquetas de sesión: la identidad
   * se toma del rol asumido por Pod Identity (arn:...:assumed-role/zt-lab-<servicio>/...).
   * Si el llamante es anónimo devuelve null y se resuelve por la dirección IP de origen.
   */
  private String desdeRol() {
    if (callerPrincipal == null) {
      return null;
    }
    int i = callerPrincipal.indexOf("assumed-role/zt-lab-");
    if (i < 0) {
      return null;
    }
    String resto = callerPrincipal.substring(i + "assumed-role/zt-lab-".length());
    int fin = resto.indexOf('/');
    return "tienda/" + (fin < 0 ? resto : resto.substring(0, fin));
  }

  /** Dirección IP de origen (sin el puerto). */
  public String ipOrigen() {
    if (sourceIpPort == null) {
      return null;
    }
    int i = sourceIpPort.lastIndexOf(':');
    return i < 0 ? sourceIpPort : sourceIpPort.substring(0, i);
  }
}
