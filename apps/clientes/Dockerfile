# Imagen de ejecución. El jar lo construye `mvn verify` en la integración continua;
# el contexto de build es apps/clientes. curl se instala para las sondas exec de
# Kubernetes (localhost:8081), necesarias porque las políticas de red bloquean el
# acceso del kubelet al pod en C1 (sección 7.2.7).
FROM eclipse-temurin:17-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && (getent passwd 1000 || useradd -r -u 1000 app) \
 && mkdir -p /app
COPY target/clientes-1.0.0.jar /app/app.jar
USER 1000
EXPOSE 8080 8443 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
