package co.edu.udistrital.ztlab.auditoria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import co.edu.udistrital.ztlab.auditoria.modelo.EventoAccesoLattice;
import co.edu.udistrital.ztlab.auditoria.modelo.EventoRed;
import co.edu.udistrital.ztlab.auditoria.proceso.Normalizador;
import co.edu.udistrital.ztlab.auditoria.proceso.ResolverIdentidad;

/** P1 y P2: tipado del registro de acceso y resolución de identidad. */
class NormalizadorTest {

  private final ResolverIdentidad resolver = new ResolverIdentidad();
  private final Normalizador n = new Normalizador(resolver);

  private static String conEtiquetas(String ip, String sa) {
    return "{\"startTime\":1730000000000,\"sourceIpPort\":\"" + ip + ":4000\","
        + "\"serviceArn\":\"arn:aws:vpc-lattice:us-east-1:111122223333:service/svc-1\","
        + "\"requestMethod\":\"GET\",\"requestPath\":\"/clientes/42\",\"responseCode\":200,"
        + "\"authDeniedReason\":null,\"callerPrincipal\":\"arn:aws:iam::111122223333:root\","
        + "\"callerPrincipalTags\":{\"kubernetes-namespace\":\"tienda\",\"kubernetes-service-account\":\"" + sa + "\"},"
        + "\"bytesSent\":10,\"bytesReceived\":20,\"duration\":5}";
  }

  private static String sinEtiquetas(String ip) {
    return "{\"startTime\":1730000000000,\"sourceIpPort\":\"" + ip + ":4000\","
        + "\"serviceArn\":\"arn:aws:vpc-lattice:us-east-1:111122223333:service/svc-1\","
        + "\"requestMethod\":\"GET\",\"requestPath\":\"/clientes/42\",\"responseCode\":403,"
        + "\"authDeniedReason\":\"Identity\",\"bytesSent\":0,\"bytesReceived\":0,\"duration\":1}";
  }

  @Test
  void p1_tipaYResuelveIdentidadDesdeEtiquetas() throws Exception {
    EventoAccesoLattice e = n.tiparLattice(conEtiquetas("10.0.16.5", "pedidos"));
    assertEquals("tienda/pedidos", e.principal());
    assertFalse(e.denegado());
    EventoRed r = n.aEventoRed(e);
    assertEquals("tienda/pedidos", r.principal());
  }

  @Test
  void p2_identidadPorIpAprendidaOLiteral() throws Exception {
    // Un registro autenticado enseña la identidad de la IP.
    n.aEventoRed(n.tiparLattice(conEtiquetas("10.0.16.9", "catalogo")));
    // Un registro sin etiquetas desde esa misma IP resuelve a la identidad aprendida.
    EventoRed aprendido = n.aEventoRed(n.tiparLattice(sinEtiquetas("10.0.16.9")));
    assertEquals("tienda/catalogo", aprendido.principal());
    // Una IP nunca vista se representa como ip:<dirección>.
    EventoRed literal = n.aEventoRed(n.tiparLattice(sinEtiquetas("10.0.32.7")));
    assertEquals("ip:10.0.32.7", literal.principal());
  }

  @Test
  void p1b_registroRealDeLattice_fechaIsoRolYSinDenegacion() throws Exception {
    String real = "{\"startTime\":\"2026-10-29T03:05:02Z\",\"sourceIpPort\":\"10.0.45.200:29821\","
        + "\"serviceArn\":\"arn:aws:vpc-lattice:us-east-1:111122223333:service/svc-1\","
        + "\"requestMethod\":\"GET\",\"requestPath\":\"/productos\",\"responseCode\":200,"
        + "\"authDeniedReason\":\"-\",\"failureReason\":\"-\","
        + "\"callerPrincipal\":\"arn:aws:sts::111122223333:assumed-role/zt-lab-portal/eks-zt-lab-portal-f8b\","
        + "\"callerPrincipalTags\":\"-\",\"bytesSent\":10,\"bytesReceived\":20,\"duration\":9}";
    EventoAccesoLattice e = n.tiparLattice(real);
    assertEquals("tienda/portal", e.principal());
    assertFalse(e.denegado());
    assertEquals(java.time.Instant.parse("2026-10-29T03:05:02Z"), e.instante());
  }

  @Test
  void p1c_denegacionTlsPassthroughSeDetectaPorFailureReason() throws Exception {
    String denegado = "{\"startTime\":\"2026-10-29T03:05:06Z\",\"sourceIpPort\":\"10.0.18.125:1254\","
        + "\"serviceArn\":\"arn:aws:vpc-lattice:us-east-1:111122223333:service/svc-2\","
        + "\"authDeniedReason\":\"-\",\"failureReason\":\"ClientAccessDenied\","
        + "\"callerPrincipalTags\":\"-\",\"bytesSent\":0,\"bytesReceived\":0,\"duration\":4}";
    EventoAccesoLattice e = n.tiparLattice(denegado);
    assertEquals(null, e.principal());
    org.junit.jupiter.api.Assertions.assertTrue(e.denegado());
  }
}
