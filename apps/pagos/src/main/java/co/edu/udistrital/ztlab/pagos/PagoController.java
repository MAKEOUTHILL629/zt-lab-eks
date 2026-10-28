package co.edu.udistrital.ztlab.pagos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/** Registro de pagos simulados (sección 7.2.2). Exige mTLS con CN = pedidos (filtro). */
@RestController
@RequestMapping("/pagos")
public class PagoController {

  public record SolicitudPago(int pedido, long montoCentavos) {}

  public record Pago(int id, int pedido, long montoCentavos, String estado) {}

  private final ConcurrentHashMap<Integer, Pago> pagos = new ConcurrentHashMap<>();
  private final AtomicInteger secuencia = new AtomicInteger();

  @PostMapping
  public Mono<ResponseEntity<Pago>> registrar(@RequestBody SolicitudPago solicitud) {
    int id = secuencia.incrementAndGet();
    Pago pago = new Pago(id, solicitud.pedido(), solicitud.montoCentavos(), "APROBADO");
    pagos.put(id, pago);
    return Mono.just(ResponseEntity.status(201).body(pago));
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<Pago>> obtener(@PathVariable int id) {
    Pago p = pagos.get(id);
    return Mono.just(p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p));
  }
}
