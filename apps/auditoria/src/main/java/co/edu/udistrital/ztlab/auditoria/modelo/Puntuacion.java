package co.edu.udistrital.ztlab.auditoria.modelo;

import java.time.Instant;

/** Resultado de puntuar una ventana con el bosque RCF (RF5). */
public record Puntuacion(String principal, Instant inicio, double[] vector,
                         double puntaje, double grado) {}
