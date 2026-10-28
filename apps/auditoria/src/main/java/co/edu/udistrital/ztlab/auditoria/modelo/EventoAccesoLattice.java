package co.edu.udistrital.ztlab.auditoria.modelo;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Tipado del registro de acceso de VPC Lattice ({lst:evento}, sección 7.3.4). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoAccesoLattice(
    long startTime, // marca de tiempo en milisegundos desde época (formato de VPC Lattice)
    String sourceIpPort,
    String serviceArn,
    String requestMethod,
    String requestPath,
    int responseCode,
    String authDeniedReason, // Service, Network, Identity o null
    String callerPrincipal,
    JsonNode callerPrincipalTags, // etiquetas de sesión de EKS Pod Identity
    long bytesSent,
    long bytesReceived,
    long duration) {

  public boolean denegado() {
    return (authDeniedReason != null && !authDeniedReason.equals("null")) || responseCode == 403;
  }

  /** Marca de tiempo del evento como Instant. */
  public Instant instante() {
    return Instant.ofEpochMilli(startTime);
  }

  /** Identidad del origen a partir de las etiquetas de sesión; null si no hay. */
  public String principal() {
    if (callerPrincipalTags == null || callerPrincipalTags.isMissingNode() || callerPrincipalTags.isNull()) {
      return null; // se resuelve por la dirección IP de origen
    }
    String ns = callerPrincipalTags.path("kubernetes-namespace").asText("");
    String sa = callerPrincipalTags.path("kubernetes-service-account").asText("");
    if (ns.isEmpty() || sa.isEmpty()) {
      return null;
    }
    return ns + "/" + sa;
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
