package com.tuempresa.relay.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Un enlace del creador a una plataforma.
 *
 * Es un @Embeddable dentro del mapa de Creador, no una entidad propia: no
 * tiene identidad por si misma ni se consulta suelta, siempre va con su
 * creador.
 */
@Embeddable
public class Conexion {

    @Column(nullable = false)
    private String url = "";

    private String handle;

    @Column(name = "channel_id")
    private String channelId;

    public Conexion() {}

    public Conexion(String url, String handle, String channelId) {
        this.url = url;
        this.handle = handle;
        this.channelId = channelId;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
}
