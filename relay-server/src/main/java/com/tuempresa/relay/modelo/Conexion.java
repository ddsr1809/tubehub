package com.tuempresa.relay.modelo;

import com.google.cloud.firestore.annotation.IgnoreExtraProperties;

/**
 * Un enlace del creador a una plataforma.
 *
 * Tiene constructor sin argumentos, getters y setters porque Firestore mapea
 * documentos por reflexión. Un record no serviría aquí: el SDK no sabe
 * construirlo.
 */
@IgnoreExtraProperties
public class Conexion {

    private String url = "";
    private String handle;
    private String channelId;

    public Conexion() {}

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
}
