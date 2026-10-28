package co.edu.udistrital.ztlab.auditoria;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import co.edu.udistrital.ztlab.auditoria.ingesta.FuenteArchivo;
import co.edu.udistrital.ztlab.auditoria.modelo.ResumenVentana;
import co.edu.udistrital.ztlab.auditoria.proceso.Agregador;
import co.edu.udistrital.ztlab.auditoria.proceso.DetectorRcf;
import co.edu.udistrital.ztlab.auditoria.proceso.Extractor;
import co.edu.udistrital.ztlab.auditoria.proceso.Metricas;
import co.edu.udistrital.ztlab.auditoria.proceso.Normalizador;
import co.edu.udistrital.ztlab.auditoria.proceso.PublicadorAlertas;
import co.edu.udistrital.ztlab.auditoria.proceso.ResolverIdentidad;
import co.edu.udistrital.ztlab.auditoria.proceso.TopologiaRegistro;

/** P8: la canalización completa procesa un archivo grabado y produce las alertas
 *  esperadas (tráfico estable de calentamiento seguido de una ráfaga anómala). */
class PipelineIntegracionTest {

  private static final long BASE = 1_730_000_000_000L; // ms

  private static String linea(long ms, String sa, String path, int code, String razon) {
    String tags = "{\"kubernetes-namespace\":\"tienda\",\"kubernetes-service-account\":\"" + sa + "\"}";
    String rz = razon == null ? "null" : "\"" + razon + "\"";
    return "{\"startTime\":" + ms + ",\"sourceIpPort\":\"10.0.16.5:4000\","
        + "\"serviceArn\":\"arn:aws:vpc-lattice:us-east-1:111122223333:service/svc\","
        + "\"requestMethod\":\"GET\",\"requestPath\":\"" + path + "\",\"responseCode\":" + code + ","
        + "\"authDeniedReason\":" + rz + ",\"callerPrincipalTags\":" + tags + ","
        + "\"bytesSent\":1,\"bytesReceived\":1,\"duration\":2}\n";
  }

  @Test
  void p8_procesaArchivoYProduceAlertas() throws Exception {
    Path archivo = Files.createTempFile("bitacora", ".jsonl");
    try (BufferedWriter w = Files.newBufferedWriter(archivo)) {
      // 400 ventanas estables (calentamiento): una consulta permitida al catálogo.
      for (int i = 0; i < 400; i++) {
        w.write(linea(BASE + i * 60_000L, "catalogo", "/productos", 200, null));
      }
      // 40 ventanas de ráfaga: muchos destinos nuevos y denegados (movimiento lateral).
      for (int i = 400; i < 440; i++) {
        for (int k = 0; k < 10; k++) {
          w.write(linea(BASE + i * 60_000L + k * 100L, "catalogo", "/clientes/" + k, 403, "Identity"));
        }
      }
    }

    ResolverIdentidad resolver = new ResolverIdentidad();
    Normalizador norm = new Normalizador(resolver);
    Agregador ag = new Agregador(60_000, 180_000);
    Extractor ex = new Extractor();
    Metricas m = new Metricas(new SimpleMeterRegistry());
    DetectorRcf det = new DetectorRcf(m);
    TopologiaRegistro topo = new TopologiaRegistro();
    PublicadorAlertas pub = new PublicadorAlertas(topo);

    FuenteArchivo fuente = new FuenteArchivo(archivo.toString());
    List<String> lineas = fuente.flujo().collectList().block();
    for (String l : lineas) {
      norm.normalizar(l).ifPresent(e -> {
        topo.registrarArista(e);
        ag.agregar(e);
      });
    }
    // Cierra todas las ventanas (en orden ascendente) y puntúa cada una.
    for (ResumenVentana v : ag.cerrarHasta(Instant.ofEpochMilli(BASE + 100_000L * 60_000L))) {
      pub.publicar(det.evaluar(v.principal(), v.inicio(), ex.vector(v)));
    }

    assertFalse(pub.alertas().isEmpty(), "la ráfaga debe producir al menos una alerta");
  }
}
