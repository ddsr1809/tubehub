package com.tuempresa.creatorhub.enlaces

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * Motor de redirección.
 *
 * Esta app nunca reproduce video. Su trabajo aquí es entregar al usuario dentro
 * de la app oficial del creador, sin navegador ni WebView. Eso es lo que
 * mantiene la infraestructura en cero y lo que evita problemas con los
 * Términos de Servicio de las plataformas.
 *
 * Estrategia, en orden:
 *   1. Intent con setPackage() sobre el enlace https. Es más fiable que los
 *      esquemas propietarios (youtube://, twitch://), que las plataformas
 *      cambian sin avisar, y no depende de que el esquema siga existiendo.
 *   2. Intent sin paquete: lo resuelve un App Link si la app está instalada,
 *      o el navegador si no.
 *   3. Aviso claro al usuario. Nunca fallamos en silencio.
 */
object Enrutador {

    private const val TAG = "Enrutador"

    /**
     * Paquetes oficiales. TikTok publica dos según la región: `musically` es
     * el internacional y `trill` aparece en varios mercados asiáticos.
     */
    private val paquetes = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "twitch" to listOf("tv.twitch.android.app"),
        "instagram" to listOf("com.instagram.android"),
        "spotify" to listOf("com.spotify.music")
    )

    private val nombresVisibles = mapOf(
        "youtube" to "YouTube",
        "tiktok" to "TikTok",
        "twitch" to "Twitch",
        "instagram" to "Instagram",
        "spotify" to "Spotify",
        "patreon" to "Patreon",
        "web" to "su página"
    )

    /** Etiqueta del botón, escrita para que se entienda sin saber de apps. */
    fun accionDe(plataforma: String) = when (plataforma) {
        "youtube" -> "Ver videos largos"
        "tiktok" -> "Ver videos cortos"
        "twitch" -> "Ver transmisiones en vivo"
        "instagram" -> "Ver fotos y reels"
        "spotify" -> "Escuchar el pódcast"
        "patreon" -> "Apoyar al creador"
        else -> "Abrir su página"
    }

    fun nombreDe(plataforma: String) = nombresVisibles[plataforma] ?: "la app original"

    /** Abre un video concreto. Es lo que ocurre al tocar una notificación. */
    fun abrirVideo(
        contexto: Context,
        plataforma: String,
        videoId: String?,
        url: String?,
        campana: String = "app"
    ) {
        val destino = when {
            !url.isNullOrBlank() -> url
            plataforma == "youtube" && !videoId.isNullOrBlank() ->
                "https://www.youtube.com/watch?v=$videoId"
            else -> null
        } ?: return avisarSinDestino(contexto)

        abrir(contexto, plataforma, conAtribucion(destino, campana))
    }

    /** Abre el perfil del creador en la plataforma elegida. */
    fun abrirCanal(contexto: Context, plataforma: String, url: String?, campana: String = "perfil") {
        if (url.isNullOrBlank()) return avisarSinDestino(contexto)
        abrir(contexto, plataforma, conAtribucion(url, campana))
    }

    private fun abrir(contexto: Context, plataforma: String, url: String) {
        val uri = Uri.parse(url)

        // 1. Intento dirigido a la app oficial.
        paquetes[plataforma].orEmpty().forEach { paquete ->
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(paquete)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                contexto.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // La app no está instalada, o está deshabilitada. Siguiente.
                Log.d(TAG, "No se pudo abrir con $paquete")
            }
        }

        // 2. Sin paquete: App Link si está, navegador si no.
        try {
            contexto.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            // 3. Ni siquiera hay navegador. Muy raro, pero pasa en dispositivos
            //    corporativos con restricciones.
            Toast.makeText(
                contexto,
                "No se pudo abrir ${nombreDe(plataforma)}. Revisa que tengas la app o un navegador instalado.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun avisarSinDestino(contexto: Context) {
        Toast.makeText(
            contexto,
            "Todavía no tenemos el enlace de este contenido.",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Conserva las etiquetas UTM al saltar entre apps.
     *
     * Se hace a mano en vez de con Uri.Builder porque este último reordena y
     * reescapa los parámetros existentes, y algunos enlaces de creador llevan
     * tokens firmados que no toleran ser reescritos.
     */
    fun conAtribucion(url: String, campana: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url

        val corte = url.indexOf('#')
        val fragmento = if (corte >= 0) url.substring(corte) else ""
        var base = if (corte >= 0) url.substring(0, corte) else url

        val etiquetas = linkedMapOf(
            "utm_source" to "relay_app",
            "utm_medium" to "push",
            "utm_campaign" to campana
        )

        etiquetas.forEach { (clave, valor) ->
            // Si el enlace ya trae esa etiqueta, respetamos la del creador.
            if (Regex("[?&]$clave=").containsMatchIn(base)) return@forEach
            base += (if (base.contains('?')) "&" else "?") +
                "$clave=" + Uri.encode(valor)
        }

        return base + fragmento
    }
}
