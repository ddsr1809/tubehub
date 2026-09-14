package com.tuempresa.creatorhub.data

import java.time.Instant

// Los nombres de los campos se mantienen deliberadamente como estaban cuando
// los datos venian de Firestore. Asi ninguna pantalla cambia: la traduccion
// desde los nombres del servidor ocurre en ApiRelay, en un solo sitio.

data class Conexion(
    val url: String = "",
    val handle: String? = null,
    val channelId: String? = null
)

data class Creador(
    val id: String = "",
    val name: String = "",
    val category: String = "otros",
    val bio: String? = null,
    val photoUrl: String? = null,
    val platforms: Map<String, Conexion> = emptyMap(),
    val active: Boolean = true
) {
    /** Plataformas en el orden en que se muestran, filtrando las vacías. */
    val conexionesOrdenadas: List<Pair<String, Conexion>>
        get() = ORDEN_PLATAFORMAS.mapNotNull { p -> platforms[p]?.let { p to it } }

    companion object {
        val ORDEN_PLATAFORMAS = listOf(
            "youtube", "tiktok", "twitch", "instagram", "spotify", "patreon", "web"
        )
    }
}

data class Publicacion(
    val id: String = "",
    val videoId: String = "",
    val creatorId: String = "",
    val creatorName: String? = null,
    val platform: String = "youtube",
    val title: String = "",
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val url: String? = null,
    // Antes era com.google.firebase.Timestamp. Ahora es un Instant del JDK:
    // el servidor manda ISO-8601 y no hace falta ninguna biblioteca externa.
    val publishedAt: Instant? = null,
    val status: String = "ok",
    val overrideUrl: String? = null,
    val overridePlatform: String? = null,
    val esEnVivo: Boolean = false,
    val tipo: String = "video"
) {
    val fueMovido: Boolean get() = status == "moved" && !overrideUrl.isNullOrBlank()

    /**
     * A dónde lleva realmente el botón. Si el equipo redirigió el contenido
     * porque lo tumbaron de la plataforma original, el destino es el nuevo.
     */
    val destino: Destino
        get() = if (fueMovido) {
            Destino(overridePlatform ?: "web", overrideUrl, null)
        } else {
            Destino(platform, url, videoId)
        }
}

data class Destino(
    val plataforma: String,
    val url: String?,
    val videoId: String?
)

data class Perfil(
    val favoritos: List<String> = emptyList(),
    val escalaTexto: String = "normal",
    val tema: String = "sistema",
    val avisos: Boolean = true
)
