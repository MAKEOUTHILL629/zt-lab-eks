package co.edu.udistrital.ztlab.auditoria.ingesta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Reproduce registros grabados desde un archivo (RNF4), para desarrollo y para la línea
 * base C0 sin consumir la nube. Si no se indica archivo, la fuente queda inactiva.
 */
@Component
@Profile("!c1")
public class FuenteArchivo implements FuenteTelemetria {

  private final String archivo;

  public FuenteArchivo(@Value("${ZT_ARCHIVO:}") String archivo) {
    this.archivo = archivo;
  }

  @Override
  public Flux<String> flujo() {
    if (archivo == null || archivo.isBlank()) {
      return Flux.never();
    }
    return Flux.using(
        () -> Files.lines(Path.of(archivo)),
        Flux::fromStream,
        Stream::close).filter(l -> !l.isBlank());
  }
}
