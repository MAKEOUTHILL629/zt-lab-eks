package co.edu.udistrital.ztlab.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Escanea también el paquete comun (filtro de firma SigV4 del modo A).
@SpringBootApplication(scanBasePackages = "co.edu.udistrital.ztlab")
public class PedidosApplication {
  public static void main(String[] args) {
    SpringApplication.run(PedidosApplication.class, args);
  }
}
