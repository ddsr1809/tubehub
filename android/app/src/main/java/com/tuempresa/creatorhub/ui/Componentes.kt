package com.tuempresa.creatorhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tuempresa.creatorhub.data.Creador

enum class VarianteBoton { PRIMARIO, SECUNDARIO, PELIGRO }

/**
 * Botón principal. 56dp de alto mínimo (no fijo: tiene que poder crecer si el
 * texto crece), etiqueta siempre escrita, nunca un icono suelto que haya que
 * interpretar.
 */
@Composable
fun BotonGrande(
    titulo: String,
    modifier: Modifier = Modifier,
    subtitulo: String? = null,
    variante: VarianteBoton = VarianteBoton.PRIMARIO,
    habilitado: Boolean = true,
    onClick: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val fondo = when (variante) {
        VarianteBoton.PRIMARIO -> esquema.primary
        VarianteBoton.SECUNDARIO -> esquema.surface
        VarianteBoton.PELIGRO -> Color.Transparent
    }
    val tinte = when (variante) {
        VarianteBoton.PRIMARIO -> esquema.onPrimary
        VarianteBoton.SECUNDARIO -> esquema.onSurface
        VarianteBoton.PELIGRO -> esquema.error
    }
    val borde = when (variante) {
        VarianteBoton.PRIMARIO -> null
        VarianteBoton.SECUNDARIO -> esquema.outline
        VarianteBoton.PELIGRO -> esquema.error
    }

    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fondo, contentColor = tinte),
        border = borde?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(horizontal = Espacio.lg, vertical = Espacio.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tactil.principal)
            .padding(bottom = Tactil.separacion)
            // Una sola etiqueta para el lector de pantalla, en vez de dos
            // fragmentos sueltos que TalkBack leería como si no tuvieran
            // relación entre sí.
            .semantics(mergeDescendants = true) {
                contentDescription = if (subtitulo != null) "$titulo. $subtitulo" else titulo
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(titulo, style = MaterialTheme.typography.labelLarge, color = tinte)
            if (subtitulo != null) {
                Text(
                    subtitulo,
                    style = MaterialTheme.typography.bodySmall,
                    color = tinte.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Fila del directorio. El área táctil abarca la fila entera, no solo el texto. */
@Composable
fun FilaCreador(
    creador: Creador,
    siguiendo: Boolean,
    onAbrir: () -> Unit,
    onSeguir: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val lugares = creador.platforms.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Espacio.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espacio.md),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Tactil.minimo)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAbrir)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "${creador.name}, ${creador.category}. Ver su perfil."
                }
                .padding(vertical = Espacio.sm)
        ) {
            Avatar(creador.photoUrl, creador.name)
            Column(Modifier.weight(1f)) {
                Text(
                    creador.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = esquema.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${creador.category.replaceFirstChar { it.uppercase() }} · " +
                        "$lugares ${if (lugares == 1) "lugar" else "lugares"} donde publica",
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(Tactil.separacion))

        OutlinedButton(
            onClick = onSeguir,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (siguiendo) esquema.primary else Color.Transparent,
                contentColor = if (siguiendo) esquema.onPrimary else esquema.onBackground
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (siguiendo) esquema.primary else esquema.outline
            ),
            modifier = Modifier
                .heightIn(min = Tactil.minimo)
                .widthIn(min = 100.dp)
                .semantics {
                    role = Role.Switch
                    contentDescription = if (siguiendo)
                        "Dejar de recibir avisos de ${creador.name}"
                    else
                        "Recibir avisos de ${creador.name}"
                }
        ) {
            Text(
                if (siguiendo) "Siguiendo" else "Seguir",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    HorizontalDivider(color = esquema.outline)
}

@Composable
fun Avatar(url: String?, nombre: String, tamano: androidx.compose.ui.unit.Dp = 52.dp) {
    val esquema = MaterialTheme.colorScheme

    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            // null a propósito: la imagen es decorativa. El nombre ya lo lee
            // TalkBack en el texto de al lado, y repetirlo sería ruido.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(tamano)
                .clip(CircleShape)
                .background(esquema.surfaceVariant)
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(tamano)
                .clip(CircleShape)
                .background(esquema.surfaceVariant)
        ) {
            Text(
                nombre.take(1).uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = esquema.onSurfaceVariant
            )
        }
    }
}

/** Pantalla vacía: siempre dice qué hacer, nunca solo "no hay nada". */
@Composable
fun Vacio(
    titulo: String,
    mensaje: String,
    accion: (@Composable () -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(Espacio.xl)
    ) {
        Text(
            titulo,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(Espacio.sm))
        Text(
            mensaje,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (accion != null) {
            Spacer(Modifier.height(Espacio.lg))
            accion()
        }
    }
}
