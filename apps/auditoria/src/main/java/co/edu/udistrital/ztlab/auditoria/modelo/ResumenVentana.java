package co.edu.udistrital.ztlab.auditoria.modelo;

import java.time.Instant;

/** Resumen de una ventana de 60 s para un principal (RF3, RF4). */
public record ResumenVentana(String principal, Instant inicio, long intentos,
                             int destinosDistintos, long denegados) {}
