package co.edu.udistrital.ztlab.catalogo;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Catálogo de productos en memoria con datos sintéticos (sección 7.2.2). */
@RestController
@RequestMapping("/productos")
public class ProductoController {

  public record Producto(int id, String nombre, long precioCentavos) {}

  private final Map<Integer, Producto> catalogo = IntStream.rangeClosed(1, 100)
      .mapToObj(i -> new Producto(i, "Producto " + i, 1000L + i * 100L))
      .collect(Collectors.toMap(Producto::id, p -> p));

  @GetMapping
  public Flux<Producto> listar() {
    return Flux.fromIterable(catalogo.values());
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<Producto>> obtener(@PathVariable int id) {
    Producto p = catalogo.get(id);
    return Mono.just(p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p));
  }
}
