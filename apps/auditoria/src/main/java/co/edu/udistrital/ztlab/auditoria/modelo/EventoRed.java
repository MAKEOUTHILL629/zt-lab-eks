package co.edu.udistrital.ztlab.auditoria.modelo;

import java.time.Instant;

/** Evento normalizado y común a las tres fuentes de telemetría (RF2, sección 7.3.4). */
public record EventoRed(Instant instante, String principal, String destino, boolean denegado) {}
