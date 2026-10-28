package co.edu.udistrital.ztlab.auditoria.modelo;

import java.time.Instant;

/** Alerta emitida cuando el grado de anomalía supera cero (RF6). */
public record Alerta(String principal, Instant inicio, double[] vector, double puntaje,
                     double grado, Instant emitida, long ttaSegundos) {}
