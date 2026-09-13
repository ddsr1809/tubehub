package com.tuempresa.relay.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * La entidad central del directorio.
 *
 * Es "un creador", no "un canal de YouTube": una persona que publica en varios
 * sitios. Esa decision de modelado permite agrupar YouTube, TikTok y Twitch
 * bajo un solo perfil, y hace que el directorio sobreviva si a alguien le
 * cierran una cuenta.
 */
@Entity
@Table(name = "creadores")
public class Creador {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String nombre = "";

    @Column(nullable = false)
    private String categoria = "otros";

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "foto_url")
    private String fotoUrl;

    @Column(nullable = false)
    private boolean activo = true;

    /**
     * Las conexiones se cargan siempre con el creador porque la interfaz las
     * necesita en cuanto muestra un perfil. Con LAZY tendriamos una consulta
     * extra por cada fila del directorio.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "conexiones", joinColumns = @JoinColumn(name = "creador_id"))
    @MapKeyColumn(name = "plataforma")
    private Map<String, Conexion> conexiones = new LinkedHashMap<>();

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getFotoUrl() { return fotoUrl; }
    public void setFotoUrl(String fotoUrl) { this.fotoUrl = fotoUrl; }

    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }

    public Map<String, Conexion> getConexiones() { return conexiones; }
    public void setConexiones(Map<String, Conexion> conexiones) {
        this.conexiones = conexiones != null ? conexiones : new LinkedHashMap<>();
    }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }

    /** ID canonico del canal de YouTube, o null si el creador no tiene. */
    @Transient
    public String getCanalDeYouTube() {
        Conexion youtube = conexiones.get("youtube");
        return youtube != null ? youtube.getChannelId() : null;
    }
}
