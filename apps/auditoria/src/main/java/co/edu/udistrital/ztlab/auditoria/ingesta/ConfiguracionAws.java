package co.edu.udistrital.ztlab.auditoria.ingesta;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;

/** Cliente asíncrono de CloudWatch Logs (solo C1). Usa la cadena de credenciales por
 *  defecto (EKS Pod Identity) y la región del entorno. */
@Configuration
@Profile("c1")
public class ConfiguracionAws {
  @Bean
  public CloudWatchLogsAsyncClient cloudWatchLogs() {
    return CloudWatchLogsAsyncClient.create();
  }
}
