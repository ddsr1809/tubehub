package com.tuempresa.relay.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Estado de una suscripcion WebSub. El panel lo lee para pintar el testigo de
 * cada creador: verde si recibe avisos, ambar si esta verificando, rojo si
 * fallo la conexion.
 */
@Entity
@Table(name = "suscripciones")
public class Suscripcion {

    public static final String PENDIENTE = "PENDIENTE_VERIFICACION";
    public static final String ACTIVA = "ACTIVA";
    public static final String CANCELADA = "CANCELADA";
    public static final String ERROR = "ERROR";

    @Id
    @Column(name = "channel_id")
    private String channelId;

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    @Column(nullable = false)
    private String modo = "subscribe";

    @Column(nullable = false)
    private String estado = PENDIENTE;

    @Column(name = "lease_segundos")
    private Long leaseSegundos;

    @Column(name = "expira_en")
    private Instant expiraEn;

    @Column(name = "solicitado_en", nullable = false)
    private Instant solicitadoEn = Instant.now();

    @Column(name = "verificado_en")
    private Instant verificadoEn;

    @Column(name = "ultimo_error", columnDefinition = "text")
    private String ultimoError;

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getModo() { return modo; }
    public void setModo(String modo) { this.modo = modo; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public Long getLeaseSegundos() { return leaseSegundos; }
    public void setLeaseSegundos(Long leaseSegundos) { this.leaseSegundos = leaseSegundos; }

    public Instant getExpiraEn() { return expiraEn; }
    public void setExpiraEn(Instant expiraEn) { this.expiraEn = expiraEn; }

    public Instant getSolicitadoEn() { return solicitadoEn; }
    public void setSolicitadoEn(Instant solicitadoEn) { this.solicitadoEn = solicitadoEn; }

    public Instant getVerificadoEn() { return verificadoEn; }
    public void setVerificadoEn(Instant verificadoEn) { this.verificadoEn = verificadoEn; }

    public String getUltimoError() { return ultimoError; }
    public void setUltimoError(String ultimoError) { this.ultimoError = ultimoError; }
}
