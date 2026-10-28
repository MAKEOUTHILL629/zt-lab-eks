package co.edu.udistrital.ztlab.auditoria.proceso;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import co.edu.udistrital.ztlab.auditoria.modelo.EventoRed;
import co.edu.udistrital.ztlab.auditoria.modelo.ResumenVentana;

/**
 * Agrega eventos por principal en ventanas de 60 s según el TIEMPO DEL EVENTO y cierra
 * cada ventana cuando han transcurrido 180 s desde su fin (RF3, sección 7.3.5). Al
 * cerrar una ventana emite un resumen para cada principal conocido, incluidos los que
 * no tuvieron actividad (RF4: aprender el silencio). No es seguro entre hilos: lo
 * invoca un solo hilo del orquestador.
 */
@Component
public class Agregador {

  private static final class Acc {
    long intentos;
    final Set<String> destinos = new HashSet<>();
    long denegados;
  }

  private final long ventanaMs;
  private final long graciaMs;
  private final TreeMap<Long, Map<String, Acc>> ventanas = new TreeMap<>();
  private final Set<String> conocidos = new LinkedHashSet<>();
  private long ultimaCerrada = Long.MIN_VALUE;

  public Agregador() {
    this(60_000L, 180_000L);
  }

  public Agregador(long ventanaMs, long graciaMs) {
    this.ventanaMs = ventanaMs;
    this.graciaMs = graciaMs;
  }

  /** Acumula un evento; devuelve false si llega tarde (ventana ya cerrada). */
  public boolean agregar(EventoRed e) {
    long s = Math.floorDiv(e.instante().toEpochMilli(), ventanaMs) * ventanaMs;
    if (s <= ultimaCerrada) {
      return false; // evento tardío: se descarta y se cuenta aparte
    }
    conocidos.add(e.principal());
    Acc a = ventanas.computeIfAbsent(s, k -> new HashMap<>())
        .computeIfAbsent(e.principal(), p -> new Acc());
    a.intentos++;
    a.destinos.add(e.destino());
    if (e.denegado()) {
      a.denegados++;
    }
    return true;
  }

  /** Cierra las ventanas cuyo fin + tolerancia ya pasó respecto de {@code limite}. */
  public List<ResumenVentana> cerrarHasta(Instant limite) {
    long lim = limite.toEpochMilli();
    List<ResumenVentana> salida = new ArrayList<>();
    while (!ventanas.isEmpty()) {
      long s = ventanas.firstKey();
      if (s + ventanaMs + graciaMs > lim) {
        break; // las siguientes son aún más recientes
      }
      Map<String, Acc> mapa = ventanas.remove(s);
      Instant inicio = Instant.ofEpochMilli(s);
      for (String p : conocidos) {
        Acc a = mapa.get(p);
        if (a == null) {
          salida.add(new ResumenVentana(p, inicio, 0, 0, 0));
        } else {
          salida.add(new ResumenVentana(p, inicio, a.intentos, a.destinos.size(), a.denegados));
        }
      }
      ultimaCerrada = s;
    }
    return salida;
  }

  public Set<String> principalesConocidos() {
    return Set.copyOf(conocidos);
  }
}
