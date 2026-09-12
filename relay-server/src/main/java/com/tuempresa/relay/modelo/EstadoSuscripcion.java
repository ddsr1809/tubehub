package com.tuempresa.relay.modelo;

/** Estado de una suscripción WebSub. El panel lo usa para pintar el testigo. */
public enum EstadoSuscripcion {
    PENDIENTE_VERIFICACION,
    ACTIVA,
    CANCELADA,
    ERROR
}
