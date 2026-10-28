package co.edu.udistrital.ztlab.pedidos;

import co.edu.udistrital.ztlab.pedidos.Dtos.Pago;
import co.edu.udistrital.ztlab.pedidos.Dtos.SolicitudPago;
import reactor.core.publisher.Mono;

/** Puerta hacia el servicio de pagos: mTLS en C1 (modo B), HTTP plano en C0. */
public interface RegistroPagos {
  Mono<Pago> registrar(SolicitudPago solicitud);
}
