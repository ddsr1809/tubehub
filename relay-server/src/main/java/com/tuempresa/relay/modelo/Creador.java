package com.tuempresa.relay.modelo;

import com.google.cloud.firestore.annotation.Exclude;
import com.google.cloud.firestore.annotation.IgnoreExtraProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * La entidad central del directorio.
 *
 * Fíjate en que no es "un canal de YouTube" sino "un creador": una persona que
 * publica en varios sitios. Esa decisión de modelado es lo que permite que la
 * app agrupe YouTube, TikTok y Twitch bajo un solo perfil, y lo que hace que
 * el directorio sobreviva si a alguien le cierran una cuenta.
 */
@IgnoreExtraProperties
public class Creador {

    private String id = "";
    private String name = "";
    private String category = "otros";
    private String bio;
    private String photoUrl;
    private Map<String, Conexion> platforms = new HashMap<>();
    private boolean active = true;

    public Creador() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public Map<String, Conexion> getPlatforms() { return platforms; }
    public void setPlatforms(Map<String, Conexion> platforms) {
        this.platforms = platforms != null ? platforms : new HashMap<>();
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /**
     * ID canónico del canal de YouTube, o null si el creador no tiene.
     *
     * @Exclude porque es derivado: si Firestore lo guardara, tendríamos el
     * mismo dato en dos sitios y tarde o temprano se desincronizarían.
     */
    @Exclude
    public String getCanalDeYouTube() {
        Conexion youtube = platforms.get("youtube");
        return youtube != null ? youtube.getChannelId() : null;
    }
}
