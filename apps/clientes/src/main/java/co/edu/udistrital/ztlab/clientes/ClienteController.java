package co.edu.udistrital.ztlab.clientes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/** Datos personales sintéticos (sensibilidad alta, sección 7.2.2). */
@RestController
@RequestMapping("/clientes")
public class ClienteController {

  public record Cliente(int id, String nombre, String correo, String ciudad) {}

  @GetMapping("/{id}")
  public Mono<ResponseEntity<Cliente>> obtener(@PathVariable int id) {
    if (id < 1 || id > 500) {
      return Mono.just(ResponseEntity.notFound().build());
    }
    Cliente c = new Cliente(id, "Cliente " + id, "cliente" + id + "@ejemplo.test", "Bogotá");
    return Mono.just(ResponseEntity.ok(c));
  }
}
