package co.edu.udistrital.ztlab.pedidos;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import co.edu.udistrital.ztlab.comun.FirmaSigV4;

/**
 * WebClient del modo A para invocar catalogo y clientes. En c1 añade el filtro de firma
 * SigV4 (presente como bean); en C0 queda sin filtro y las llamadas van en HTTP plano.
 */
@Configuration
public class ConfiguracionModoA {

  @Bean
  WebClient clienteModoA(WebClient.Builder constructor, ObjectProvider<FirmaSigV4> firma) {
    firma.ifAvailable(constructor::filter);
    return constructor.build();
  }
}
