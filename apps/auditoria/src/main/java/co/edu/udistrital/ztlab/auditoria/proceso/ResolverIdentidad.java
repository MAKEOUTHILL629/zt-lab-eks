package co.edu.udistrital.ztlab.auditoria.proceso;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tabla de correspondencia dirección IP -> identidad, aprendida de los registros
 * autenticados (sección 7.3.4). Si una IP no tiene identidad conocida, se representa
 * como ip:&lt;dirección&gt; para que también se perfile.
 */
@Component
public class ResolverIdentidad {
  private final ConcurrentHashMap<String, String> porIp = new ConcurrentHashMap<>();

  public void aprender(String ip, String principal) {
    if (ip != null && principal != null) {
      porIp.put(ip, principal);
    }
  }

  public String resolver(String ip) {
    if (ip == null) {
      return "ip:desconocida";
    }
    String p = porIp.get(ip);
    return p != null ? p : "ip:" + ip;
  }
}
