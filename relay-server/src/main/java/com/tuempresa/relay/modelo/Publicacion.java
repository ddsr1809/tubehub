package com.tuempresa.relay.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Un video detectado por WebSub y enriquecido con la Data API. */
@Entity
@Table(name = "publicaciones")
public class Publicacion {

    public static final String OK = "ok";
    public static final String MOVIDO = "moved";
    public static final String RETIRADO = "removed";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "video_id", nullable = false, unique = true)
    private String videoId;

    @Column(name = "creador_id", nullable = false)
    private UUID creadorId;

    @Column(nullable = false)
    private String plataforma = "youtube";

    @Column(nullable = false, columnDefinition = "text")
    private String titulo = "Video nuevo";

    @Column(columnDefinition = "text")
    private String descripcion;

    @Column(name = "miniatura_url", columnDefinition = "text")
    private String miniaturaUrl;

    @Column(columnDefinition = "text")
    private String url;

    private String duracion;

    @Column(nullable = false)
    private String tipo = "video";

    @Column(name = "en_vivo", nullable = false)
    private boolean enVivo = false;

    @Column(nullable = false)
    private String estado = OK;

    /** Redireccion de emergencia si la plataforma original tumba el video. */
    @Column(name = "destino_url", columnDefinition = "text")
    private String destinoUrl;

    @Column(name = "destino_plataforma")
    private String destinoPlataforma;

    @Column(nullable = false)
    private boolean notificado = false;

    @Column(nullable = false)
    private int reportes = 0;

    @Column(name = "publicado_en")
    private Instant publicadoEn;

    @Column(name = "detectado_en", nullable = false)
    private Instant detectadoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }

    public UUID getCreadorId() { return creadorId; }
    public void setCreadorId(UUID creadorId) { this.creadorId = creadorId; }

    public String getPlataforma() { return plataforma; }
    public void setPlataforma(String plataforma) { this.plataforma = plataforma; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getMiniaturaUrl() { return miniaturaUrl; }
    public void setMiniaturaUrl(String miniaturaUrl) { this.miniaturaUrl = miniaturaUrl; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDuracion() { return duracion; }
    public void setDuracion(String duracion) { this.duracion = duracion; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public boolean isEnVivo() { return enVivo; }
    public void setEnVivo(boolean enVivo) { this.enVivo = enVivo; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getDestinoUrl() { return destinoUrl; }
    public void setDestinoUrl(String destinoUrl) { this.destinoUrl = destinoUrl; }

    public String getDestinoPlataforma() { return destinoPlataforma; }
    public void setDestinoPlataforma(String destinoPlataforma) { this.destinoPlataforma = destinoPlataforma; }

    public boolean isNotificado() { return notificado; }
    public void setNotificado(boolean notificado) { this.notificado = notificado; }

    public int getReportes() { return reportes; }
    public void setReportes(int reportes) { this.reportes = reportes; }

    public Instant getPublicadoEn() { return publicadoEn; }
    public void setPublicadoEn(Instant publicadoEn) { this.publicadoEn = publicadoEn; }

    public Instant getDetectadoEn() { return detectadoEn; }
    public void setDetectadoEn(Instant detectadoEn) { this.detectadoEn = detectadoEn; }
}
