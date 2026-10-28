package co.edu.udistrital.ztlab.pedidos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Tipos de datos del servicio de pedidos y de las respuestas de sus dependencias. */
public final class Dtos {
  private Dtos() {}

  public record SolicitudPedido(int cliente, int producto, int cantidad) {}

  public record Pedido(int id, int cliente, int producto, int cantidad, long totalCentavos, String estado) {}

  public record SolicitudPago(int pedido, long montoCentavos) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Pago(int id, int pedido, long montoCentavos, String estado) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Producto(int id, String nombre, long precioCentavos) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Cliente(int id, String nombre, String correo, String ciudad) {}
}
