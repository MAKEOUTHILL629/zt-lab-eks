package co.edu.udistrital.ztlab.auditoria.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.udistrital.ztlab.auditoria.modelo.Alerta;
import co.edu.udistrital.ztlab.auditoria.modelo.Puntuacion;
import co.edu.udistrital.ztlab.auditoria.proceso.Metricas;
import co.edu.udistrital.ztlab.auditoria.proceso.PublicadorAlertas;
import co.edu.udistrital.ztlab.auditoria.proceso.TopologiaRegistro;

/**
 * API REST de solo lectura del servicio de auditoría ({tab:api}). Solo es accesible
 * desde el espacio de nombres observabilidad (política de red auditoria, b5).
 */
@RestController
@RequestMapping("/api/v1")
public class ApiAuditoria {

  public record AlertaDto(String principal, String inicio, double puntaje, double grado, long tta) {}

  public record PuntajeDto(String principal, String inicio, double x1, double x2, double x3,
                           double puntaje, double grado) {}

  private final PublicadorAlertas publicador;
  private final TopologiaRegistro topologia;
  private final Metricas metricas;

  public ApiAuditoria(PublicadorAlertas publicador, TopologiaRegistro topologia, Metricas metricas) {
    this.publicador = publicador;
    this.topologia = topologia;
    this.metricas = metricas;
  }

  @GetMapping("/alertas")
  public List<AlertaDto> alertas(@RequestParam(required = false) String desde,
                                 @RequestParam(required = false) String principal) {
    Instant limite = desde == null || desde.isBlank() ? Instant.EPOCH : Instant.parse(desde);
    return publicador.alertas().stream()
        .filter(a -> principal == null || principal.isBlank() || a.principal().equals(principal))
        .filter(a -> a.inicio().isAfter(limite) || a.inicio().equals(limite))
        .map(a -> new AlertaDto(a.principal(), a.inicio().toString(), a.puntaje(), a.grado(), a.ttaSegundos()))
        .toList();
  }

  @GetMapping("/puntajes")
  public List<PuntajeDto> puntajes(@RequestParam(required = false) String principal) {
    return publicador.puntajes().stream()
        .filter(p -> principal == null || principal.isBlank() || p.principal().equals(principal))
        .map(ApiAuditoria::aPuntaje)
        .toList();
  }

  @GetMapping("/topologia/nodos")
  public List<TopologiaRegistro.Nodo> nodos() {
    return topologia.nodos();
  }

  @GetMapping("/topologia/aristas")
  public List<TopologiaRegistro.Arista> aristas() {
    return topologia.aristas();
  }

  @GetMapping("/estado")
  public Map<String, Object> estado() {
    return Map.of(
        "eventos", metricas.totalEventos(),
        "tardios", metricas.totalTardios(),
        "muestreadas", metricas.totalMuestreadas());
  }

  private static PuntajeDto aPuntaje(Puntuacion p) {
    double[] v = p.vector();
    return new PuntajeDto(p.principal(), p.inicio().toString(),
        v.length > 0 ? v[0] : 0, v.length > 1 ? v[1] : 0, v.length > 2 ? v[2] : 0,
        p.puntaje(), p.grado());
  }
}
