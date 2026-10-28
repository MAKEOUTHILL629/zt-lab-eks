package co.edu.udistrital.ztlab.pedidos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import co.edu.udistrital.ztlab.pedidos.Dtos.Cliente;
import co.edu.udistrital.ztlab.pedidos.Dtos.Pedido;
import co.edu.udistrital.ztlab.pedidos.Dtos.Producto;
import co.edu.udistrital.ztlab.pedidos.Dtos.SolicitudPago;
import co.edu.udistrital.ztlab.pedidos.Dtos.SolicitudPedido;
import reactor.core.publisher.Mono;

/**
 * Gestión de pedidos (sección 7.2.2). Al crear un pedido consulta el producto y el
 * cliente (modo A) y registra el pago (modo B); cada dependencia es una arista
 * permitida de la matriz de autorización ({tab:autorizacion}).
 */
@RestController
@RequestMapping("/pedidos")
public class PedidoController {

  private final WebClient modoA;
  private final RegistroPagos pagos;
  private final String catalogoUrl;
  private final String clientesUrl;

  private final ConcurrentHashMap<Integer, Pedido> pedidos = new ConcurrentHashMap<>();
  private final AtomicInteger secuencia = new AtomicInteger();

  public PedidoController(WebClient clienteModoA, RegistroPagos pagos,
                          @Value("${CATALOGO_URL}") String catalogoUrl,
                          @Value("${CLIENTES_URL}") String clientesUrl) {
    this.modoA = clienteModoA;
    this.pagos = pagos;
    this.catalogoUrl = catalogoUrl;
    this.clientesUrl = clientesUrl;
  }

  @PostMapping
  public Mono<ResponseEntity<Pedido>> crear(@RequestBody SolicitudPedido solicitud) {
    Mono<Producto> producto = modoA.get()
        .uri(catalogoUrl + "/productos/" + solicitud.producto())
        .retrieve().bodyToMono(Producto.class);
    Mono<Cliente> cliente = modoA.get()
        .uri(clientesUrl + "/clientes/" + solicitud.cliente())
        .retrieve().bodyToMono(Cliente.class);

    return Mono.zip(producto, cliente).flatMap(t -> {
      int id = secuencia.incrementAndGet();
      long total = t.getT1().precioCentavos() * solicitud.cantidad();
      return pagos.registrar(new SolicitudPago(id, total)).map(pago -> {
        Pedido pedido = new Pedido(id, solicitud.cliente(), solicitud.producto(),
            solicitud.cantidad(), total, pago.estado());
        pedidos.put(id, pedido);
        return ResponseEntity.status(201).body(pedido);
      });
    });
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<Pedido>> obtener(@PathVariable int id) {
    Pedido p = pedidos.get(id);
    return Mono.just(p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p));
  }
}
