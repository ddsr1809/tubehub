package com.tuempresa.relay.modelo;

import java.util.List;

/**
 * Nombres de colecciones y catálogos.
 *
 * Estos nombres coinciden letra por letra con los que leen las apps de Android
 * y iOS directamente desde Firestore. Cambiar uno aquí y no allá no produce un
 * error: el campo llega nulo y se ve como un hueco en la interfaz.
 */
public final class Colecciones {

    private Colecciones() {}

    public static final String CREADORES = "creators";
    public static final String VIDEOS = "videos";
    public static final String USUARIOS = "users";
    public static final String REPORTES = "reports";
    public static final String WEBSUB = "websub";
    public static final String TOKENS_PRIVADOS = "privateTokens";

    public static final List<String> PLATAFORMAS = List.of(
            "youtube", "tiktok", "twitch", "instagram", "spotify", "patreon", "web"
    );

    public static final List<String> CATEGORIAS = List.of(
            "cine", "comida", "politica", "musica", "salud", "noticias", "tecnologia", "otros"
    );
}
