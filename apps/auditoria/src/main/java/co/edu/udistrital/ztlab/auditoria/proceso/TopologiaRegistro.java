package co.edu.udistrital.ztlab.auditoria.proceso;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

import co.edu.udistrital.ztlab.auditoria.modelo.EventoRed;

/** Mantiene el grafo de topología Este-Oeste para el panel de Grafana (sección 7.3.8). */
@Component
public class TopologiaRegistro {

  public record Nodo(String id, String titulo, double grado) {}

  public record Arista(String id, String origen, String destino, long peticiones, long denegaciones) {}

  private static final class Contadores {
    final LongAdder peticiones = new LongAdder();
    final LongAdder denegaciones = new LongAdder();
  }

  private final ConcurrentHashMap<String, Double> grados = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Contadores> aristas = new ConcurrentHashMap<>();

  public void registrarArista(EventoRed e) {
    grados.putIfAbsent(e.principal(), 0.0);
    Contadores c = aristas.computeIfAbsent(e.principal() + "|" + e.destino(), k -> new Contadores());
    c.peticiones.increment();
    if (e.denegado()) {
      c.denegaciones.increment();
    }
  }

  public void actualizarNodo(String principal, double grado) {
    grados.put(principal, grado);
  }

  public List<Nodo> nodos() {
    return grados.entrySet().stream()
        .map(en -> new Nodo(en.getKey(), en.getKey(), en.getValue()))
        .toList();
  }

  public List<Arista> aristas() {
    return aristas.entrySet().stream().map(en -> {
      String[] par = en.getKey().split("\\|", 2);
      return new Arista(en.getKey(), par[0], par.length > 1 ? par[1] : "",
          en.getValue().peticiones.sum(), en.getValue().denegaciones.sum());
    }).toList();
  }
}
