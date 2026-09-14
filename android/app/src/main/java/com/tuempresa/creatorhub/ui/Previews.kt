package com.tuempresa.creatorhub.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import java.time.Instant
import com.tuempresa.creatorhub.EstadoApp
import com.tuempresa.creatorhub.data.Conexion
import com.tuempresa.creatorhub.data.Creador
import com.tuempresa.creatorhub.data.Perfil
import com.tuempresa.creatorhub.data.Publicacion


// Previews.
//
// Sirven para dos cosas distintas:
//
//  1. Ver cómo queda la pantalla sin compilar ni instalar nada. Basta con
//     abrir este archivo y pulsar "Split" arriba a la derecha del editor.
//
//  2. Comprobar accesibilidad de verdad. @VistaPreviaAccesible renderiza la
//     misma pantalla con el texto al 100% y al 200%, que es el escenario que
//     exige WCAG 1.4.4 y el que más suele romperse. Si algo se corta o se
//     desborda, aquí se ve al instante en lugar de descubrirlo cuando un
//     usuario mayor ya no puede leer un botón.
//
// Los datos de abajo son inventados: las previews no tocan Firebase ni red.

// -----------------------------------------------------------------------------
// Anotaciones múltiples
// -----------------------------------------------------------------------------

/** Modo claro y oscuro de un tirón. */
@Preview(name = "Claro", showBackground = true, backgroundColor = 0xFFFAFAF8)
@Preview(
    name = "Oscuro",
    showBackground = true,
    backgroundColor = 0xFF16181C,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
annotation class VistaPreviaTemas

/** El mismo diseño con la letra del sistema al 100% y al 200%. */
@Preview(name = "Letra normal", showBackground = true, backgroundColor = 0xFF16181C,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Letra al 200%", showBackground = true, backgroundColor = 0xFF16181C,
    uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 2.0f, heightDp = 900)
annotation class VistaPreviaAccesible

// -----------------------------------------------------------------------------
// Datos de ejemplo
// -----------------------------------------------------------------------------

private val juan = Creador(
    id = "juan",
    name = "Juan Pérez",
    category = "comida",
    bio = "Recetas caseras sin complicaciones. Un video nuevo cada martes.",
    photoUrl = null,
    platforms = mapOf(
        "youtube" to Conexion(url = "https://www.youtube.com/channel/UC123", channelId = "UC123"),
        "tiktok" to Conexion(url = "https://www.tiktok.com/@juanperez", handle = "juanperez"),
        "instagram" to Conexion(url = "https://www.instagram.com/juanperez")
    )
)

private val ana = Creador(
    id = "ana",
    name = "Ana Ruiz",
    category = "cine",
    bio = "Reseñas de cine clásico.",
    platforms = mapOf("youtube" to Conexion(url = "https://www.youtube.com/channel/UC456"))
)

private val luis = Creador(
    id = "luis",
    name = "Luis Mendoza",
    category = "noticias",
    platforms = mapOf(
        "youtube" to Conexion(url = "https://www.youtube.com/channel/UC789"),
        "twitch" to Conexion(url = "https://www.twitch.tv/luismendoza")
    )
)

private val creadores = listOf(juan, ana, luis)

private val publicaciones = listOf(
    Publicacion(
        id = "v1",
        videoId = "v1",
        creatorId = "juan",
        creatorName = "Juan Pérez",
        title = "Pan de muerto casero: la receta de mi abuela paso a paso",
        thumbnailUrl = null,
        url = "https://www.youtube.com/watch?v=v1",
        publishedAt = Instant.ofEpochMilli(
            System.currentTimeMillis() - 45 * 60 * 1000L
        )
    ),
    Publicacion(
        id = "v2",
        videoId = "v2",
        creatorId = "ana",
        creatorName = "Ana Ruiz",
        title = "Por qué Casablanca sigue funcionando 80 años después",
        url = "https://vimeo.com/respaldo",
        status = "moved",
        overrideUrl = "https://vimeo.com/respaldo",
        overridePlatform = "web",
        publishedAt = Instant.ofEpochMilli(
            System.currentTimeMillis() - 5 * 60 * 60 * 1000L
        )
    )
)

private val perfilConFavoritos = Perfil(favoritos = listOf("juan", "ana"), tema = "oscuro")

/** Envoltorio para que cada preview salga con el tema real de la app. */
@Composable
private fun Marco(
    tema: String = "oscuro",
    escala: EscalaTexto = EscalaTexto.NORMAL,
    contenido: @Composable () -> Unit
) {
    TemaRelay(preferencia = tema, escala = escala) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) { contenido() }
    }
}

// -----------------------------------------------------------------------------
// Componentes sueltos
// -----------------------------------------------------------------------------

@VistaPreviaTemas
@Composable
private fun PreviaBotones() {
    Marco {
        Column(Modifier.padding(Espacio.md)) {
            BotonGrande(
                titulo = "Ver el video",
                subtitulo = "Se abre en YouTube",
                onClick = {}
            )
            BotonGrande(
                titulo = "Ver videos cortos",
                subtitulo = "Se abre la app de TikTok",
                variante = VarianteBoton.SECUNDARIO,
                onClick = {}
            )
            BotonGrande(
                titulo = "Borrar mi cuenta",
                variante = VarianteBoton.PELIGRO,
                onClick = {}
            )
            BotonGrande(
                titulo = "Guardar con Google",
                habilitado = false,
                onClick = {}
            )
        }
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaFilasDelDirectorio() {
    Marco {
        Column(Modifier.padding(Espacio.md)) {
            FilaCreador(juan, siguiendo = true, onAbrir = {}, onSeguir = {})
            FilaCreador(ana, siguiendo = false, onAbrir = {}, onSeguir = {})
            FilaCreador(luis, siguiendo = false, onAbrir = {}, onSeguir = {})
        }
    }
}

// -----------------------------------------------------------------------------
// Pantallas completas
// -----------------------------------------------------------------------------

@VistaPreviaTemas
@Composable
private fun PreviaNovedades() {
    Marco {
        NovedadesPantalla(
            publicaciones = publicaciones,
            hayFavoritos = true,
            cuantosFavoritos = 2,
            onIrAlDirectorio = {},
            onReportar = {}
        )
    }
}

/** El caso vacío importa tanto como el lleno: es la primera pantalla que ve alguien. */
@VistaPreviaTemas
@Composable
private fun PreviaNovedadesVacio() {
    Marco {
        NovedadesPantalla(
            publicaciones = emptyList(),
            hayFavoritos = false,
            cuantosFavoritos = 0,
            onIrAlDirectorio = {},
            onReportar = {}
        )
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaDirectorio() {
    Marco {
        DirectorioPantalla(
            creadores = creadores,
            sigue = { it == "juan" },
            onSeguir = {},
            onAbrirCreador = {}
        )
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaPerfilDeCreador() {
    Marco {
        CreadorPantalla(
            creador = juan,
            siguiendo = false,
            onSeguir = {},
            onVolver = {}
        )
    }
}

@VistaPreviaTemas
@Composable
private fun PreviaAjustes() {
    Marco {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                creadores = creadores,
                perfil = perfilConFavoritos,
                esAnonimo = true
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}

/** Cómo se ve Ajustes con la cuenta ya guardada. */
@Preview(name = "Ajustes con sesión", showBackground = true, heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviaAjustesConSesion() {
    Marco {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                perfil = perfilConFavoritos,
                esAnonimo = false,
                correo = "juan.perez@gmail.com"
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}
