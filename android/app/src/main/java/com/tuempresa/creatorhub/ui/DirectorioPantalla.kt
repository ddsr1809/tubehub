package com.tuempresa.creatorhub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tuempresa.creatorhub.data.Creador
import com.tuempresa.creatorhub.enlaces.Enrutador

// El directorio es cerrado: solo aparecen los creadores que el equipo aprobó.
// Por eso no hay buscador abierto hacia todo YouTube, y por eso la app no cae
// en la categoría de "directorio genérico" que la Guideline 3.2.2 de Apple
// rechaza y que Play Store también penaliza.

private val TEMAS = listOf(
    "todos" to "Todos",
    "comida" to "Comida",
    "cine" to "Cine",
    "politica" to "Política",
    "musica" to "Música",
    "noticias" to "Noticias",
    "salud" to "Salud",
    "tecnologia" to "Tecnología",
    "otros" to "Otros"
)

@Composable
fun DirectorioPantalla(
    creadores: List<Creador>,
    sigue: (String) -> Boolean,
    onSeguir: (String) -> Unit,
    onAbrirCreador: (String) -> Unit
) {
    var tema by remember { mutableStateOf("todos") }
    val esquema = MaterialTheme.colorScheme

    val visibles = remember(creadores, tema) {
        if (tema == "todos") creadores else creadores.filter { it.category == tema }
    }

    // Solo mostramos los temas que de verdad tienen a alguien dentro. Una
    // pestaña vacía es una promesa incumplida.
    val temasConGente = remember(creadores) {
        val usados = creadores.map { it.category }.toSet()
        TEMAS.filter { it.first == "todos" || it.first in usados }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Directorio",
            style = MaterialTheme.typography.displayLarge,
            color = esquema.onBackground,
            modifier = Modifier.padding(start = Espacio.md, end = Espacio.md, top = Espacio.sm)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Espacio.md, vertical = Espacio.md)
        ) {
            temasConGente.forEach { (clave, nombre) ->
                val activo = clave == tema
                OutlinedButton(
                    onClick = { tema = clave },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (activo) esquema.primary else Color.Transparent,
                        contentColor = if (activo) esquema.onPrimary else esquema.onBackground
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (activo) esquema.primary else esquema.outline
                    ),
                    modifier = Modifier
                        .heightIn(min = Tactil.minimo)
                        .semantics { role = Role.Tab }
                ) {
                    Text(nombre, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (visibles.isEmpty()) {
            Vacio(
                titulo = "Nada en este tema todavía",
                mensaje = "Estamos sumando creadores poco a poco. Prueba con otro tema."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visibles, key = { it.id }) { creador ->
                    FilaCreador(
                        creador = creador,
                        siguiendo = sigue(creador.id),
                        onAbrir = { onAbrirCreador(creador.id) },
                        onSeguir = { onSeguir(creador.id) }
                    )
                }
            }
        }
    }
}

/**
 * Perfil del creador.
 *
 * Aquí se materializa la idea del "Creador" como entidad, no del canal. Una
 * persona publica en varios lugares; la app los junta bajo un solo perfil y
 * cada botón dice en palabras qué va a pasar al tocarlo.
 */
@Composable
fun CreadorPantalla(
    creador: Creador?,
    siguiendo: Boolean,
    onSeguir: () -> Unit,
    onVolver: () -> Unit
) {
    val contexto = LocalContext.current
    val esquema = MaterialTheme.colorScheme

    if (creador == null) {
        Vacio(
            titulo = "Este creador ya no está",
            mensaje = "Puede que lo hayamos retirado del directorio. Vuelve al listado para ver a los demás.",
            accion = { BotonGrande("Volver al directorio", onClick = onVolver) }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Espacio.lg)
        ) {
            Avatar(creador.photoUrl, creador.name, tamano = 88.dp)
            Text(
                creador.name,
                style = MaterialTheme.typography.displayLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.md)
            )
            if (!creador.bio.isNullOrBlank()) {
                Text(
                    creador.bio,
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = Espacio.sm)
                )
            }
        }

        BotonGrande(
            titulo = if (siguiendo) "Ya recibes sus avisos" else "Avísame cuando publique",
            subtitulo = if (siguiendo)
                "Toca para dejar de recibirlos"
            else
                "Te llegará una notificación a este teléfono",
            variante = if (siguiendo) VarianteBoton.SECUNDARIO else VarianteBoton.PRIMARIO,
            onClick = onSeguir
        )

        Text(
            "Dónde publica",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
        )

        val conexiones = creador.conexionesOrdenadas
        if (conexiones.isEmpty()) {
            Text(
                "Todavía no hemos agregado sus enlaces.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant
            )
        }

        conexiones.forEach { (plataforma, conexion) ->
            BotonGrande(
                titulo = Enrutador.accionDe(plataforma),
                subtitulo = "Se abre la app de ${Enrutador.nombreDe(plataforma)}",
                variante = VarianteBoton.SECUNDARIO,
                onClick = {
                    Enrutador.abrirCanal(contexto, plataforma, conexion.url, campana = "perfil_creador")
                }
            )
        }

        Text(
            "Los videos se ven en la app oficial de cada plataforma. Esta app solo te avisa y te lleva hasta allá.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.xxl)
        )
    }
}
