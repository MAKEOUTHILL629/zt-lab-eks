package co.edu.udistrital.ztlab.auditoria.proceso;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import co.edu.udistrital.ztlab.auditoria.modelo.EventoAccesoLattice;

/** Métricas Prometheus del servicio (sección 7.3.9). Nombres zt_*. */
@Component
public class Metricas {

  private static final class Estado {
    volatile double puntaje;
    volatile double grado;
  }

  private final MeterRegistry registro;
  private final Counter eventos;
  private final Counter tardios;
  private final Counter muestreadas;
  private final Timer puntuacion;
  private final DistributionSummary duracion;
  private final ConcurrentHashMap<String, Estado> estados = new ConcurrentHashMap<>();

  public Metricas(MeterRegistry registro) {
    this.registro = registro;
    this.eventos = Counter.builder("zt.ingesta.eventos").register(registro);
    this.tardios = Counter.builder("zt.ingesta.tardios").register(registro);
    this.muestreadas = Counter.builder("zt.ingesta.muestreadas").register(registro);
    this.puntuacion = Timer.builder("zt.puntuacion.latencia")
        .publishPercentileHistogram().register(registro);
    this.duracion = DistributionSummary.builder("zt.lattice.duracion")
        .baseUnit("ms").publishPercentileHistogram().register(registro);
  }

  public void contarEvento() { eventos.increment(); }

  public void contarTardio() { tardios.increment(); }

  public void contarMuestreada() { muestreadas.increment(); }

  public Timer puntuacionTimer() { return puntuacion; }

  public double totalEventos() { return eventos.count(); }

  public double totalTardios() { return tardios.count(); }

  public double totalMuestreadas() { return muestreadas.count(); }

  /** Deriva de un registro de acceso las métricas de peticiones y duración. */
  public void registrarAcceso(EventoAccesoLattice e) {
    String servicio = servicioCorto(e.serviceArn());
    String razon = e.authDeniedReason() == null ? "null" : e.authDeniedReason();
    String resultado = e.denegado() ? "denegado" : "permitido";
    registro.counter("zt.lattice.peticiones", "servicio", servicio,
        "razon", razon, "resultado", resultado).increment();
    duracion.record(e.duration());
  }

  public void registrarBloqueo(String origen, String destino) {
    registro.counter("zt.red.bloqueos", "origen", origen, "destino", destino).increment();
  }

  public void actualizarRcf(String principal, double puntaje, double grado) {
    Estado s = estados.computeIfAbsent(principal, p -> {
      Estado nuevo = new Estado();
      Gauge.builder("zt.rcf.puntaje", nuevo, x -> x.puntaje).tag("principal", p).register(registro);
      Gauge.builder("zt.rcf.grado", nuevo, x -> x.grado).tag("principal", p).register(registro);
      return nuevo;
    });
    s.puntaje = puntaje;
    s.grado = grado;
  }

  private static String servicioCorto(String arn) {
    if (arn == null) {
      return "desconocido";
    }
    int i = arn.lastIndexOf('/');
    return i < 0 ? arn : arn.substring(i + 1);
  }
}
