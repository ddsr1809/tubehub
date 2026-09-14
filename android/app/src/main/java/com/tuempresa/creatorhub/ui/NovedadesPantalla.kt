package com.tuempresa.creatorhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant
import com.tuempresa.creatorhub.data.Publicacion
import com.tuempresa.creatorhub.enlaces.Enrutador
import java.text.SimpleDateFormat
import java.util.Locale

// Nada de scroll infinito ni recomendaciones. Aquí solo aparece lo que
// publicaron las personas que el usuario eligió seguir, en orden de tiempo.
// Esa previsibilidad es la propuesta de valor entera.

@Composable
fun NovedadesPantalla(
    publicaciones: List<Publicacion>,
    hayFavoritos: Boolean,
    cuantosFavoritos: Int,
    onIrAlDirectorio: () -> Unit,
    onReportar: (Publicacion) -> Unit
) {
    val contexto = LocalContext.current

    if (!hayFavoritos) {
        Vacio(
            titulo = "Todavía no sigues a nadie",
            mensaje = "Elige a los creadores que te interesan y te avisaremos aquí cada vez que publiquen algo nuevo.",
            accion = { BotonGrande("Ver el directorio", onClick = onIrAlDirectorio) }
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(Espacio.md),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Novedades",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = Espacio.lg)
            )
        }

        if (publicaciones.isEmpty()) {
            item {
                Vacio(
                    titulo = "Sin novedades por ahora",
                    mensaje = "Sigues a $cuantosFavoritos " +
                        (if (cuantosFavoritos == 1) "creador" else "creadores") +
                        ". En cuanto alguno publique, el aviso llega a este teléfono."
                )
            }
        }

        items(publicaciones, key = { it.id }) { publicacion ->
            TarjetaPublicacion(
                publicacion = publicacion,
                onAbrir = {
                    val destino = publicacion.destino
                    Enrutador.abrirVideo(
                        contexto,
                        destino.plataforma,
                        destino.videoId,
                        destino.url,
                        campana = "novedades"
                    )
                },
                onReportar = { onReportar(publicacion) }
            )
        }
    }
}

@Composable
private fun TarjetaPublicacion(
    publicacion: Publicacion,
    onAbrir: () -> Unit,
    onReportar: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val destino = publicacion.destino

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.lg)
            .clip(RoundedCornerShape(20.dp))
            .background(esquema.surface)
            .border(1.dp, esquema.outline, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onAbrir)
                .semantics {
                    contentDescription =
                        "Abrir el video ${publicacion.title} de ${publicacion.creatorName ?: ""}"
                }
        ) {
            if (!publicacion.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = publicacion.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(esquema.surfaceVariant)
                )
            }

            Column(Modifier.padding(Espacio.md)) {
                Text(
                    "${publicacion.creatorName ?: ""} · ${tiempoRelativo(publicacion.publishedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant
                )
                Text(
                    publicacion.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = esquema.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (publicacion.fueMovido) {
                    Text(
                        "Este video cambió de lugar. El botón te lleva al sitio nuevo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.primary,
                        modifier = Modifier.padding(top = Espacio.sm)
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = Espacio.md).padding(bottom = Espacio.sm)) {
            BotonGrande(
                titulo = if (publicacion.esEnVivo) "Ver en vivo" else "Ver el video",
                subtitulo = "Se abre en ${Enrutador.nombreDe(destino.plataforma)}",
                onClick = onAbrir
            )

            TextButton(
                onClick = onReportar,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = Tactil.minimo)
            ) {
                Text(
                    "El enlace no funciona",
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Fechas en palabras. "hace 2 horas" se entiende de un vistazo; una marca
 * como 09/09/2026 15:04 obliga a hacer la resta mentalmente.
 */
private fun tiempoRelativo(marca: Instant?): String {
    val fecha = marca?.let { java.util.Date.from(it) } ?: return ""
    val minutos = (System.currentTimeMillis() - fecha.time) / 60_000

    return when {
        minutos < 2 -> "hace un momento"
        minutos < 60 -> "hace $minutos minutos"
        minutos < 120 -> "hace una hora"
        minutos < 1440 -> "hace ${minutos / 60} horas"
        minutos < 2880 -> "ayer"
        minutos < 10080 -> "hace ${minutos / 1440} días"
        else -> SimpleDateFormat("d 'de' MMMM", Locale("es", "MX")).format(fecha)
    }
}
