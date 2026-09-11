package com.tuempresa.creatorhub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

// Sistema de diseño.
//
// Todo lo que hay aquí sale de un requisito concreto de WCAG 2.2 AA o de la
// ergonomía del pulgar, no de una preferencia estética:
//
//  · 1.4.3 Contraste — mínimo legal 4.5:1. Estos valores llegan a 8:1 o más
//    porque el público objetivo tiene visión reducida.
//  · 2.5.8 Tamaño del objetivo — mínimo 24dp. Partimos de 48 y usamos 56 en
//    las acciones principales, con separación para que un temblor no active
//    el botón vecino.
//  · 1.4.4 Redimensionar texto — todo en sp, nunca en dp, y contenedores que
//    crecen con el contenido.
//
// El fondo oscuro es gris profundo, no negro absoluto: en OLED el texto blanco
// sobre negro puro "vibra" y cansa la vista.

object Colores {
    val Carbon = Color(0xFF16181C)
    val Superficie = Color(0xFF21252C)
    val SuperficieAlta = Color(0xFF2B3038)
    val Borde = Color(0xFF3A414B)
    val Texto = Color(0xFFF2F4F6)      // 15.2:1 sobre Carbon
    val TextoSuave = Color(0xFFA8B0BA) //  8.1:1
    val Ambar = Color(0xFFFFB020)      //  9.4:1
    val AmbarTexto = Color(0xFF241A05)
    val Exito = Color(0xFF4FD1A5)
    val Peligro = Color(0xFFFF7A69)

    val FondoClaro = Color(0xFFFAFAF8)
    val SuperficieClara = Color(0xFFFFFFFF)
    val BordeClaro = Color(0xFFC9CDD3)
    val TextoClaro = Color(0xFF14161A)     // 16.1:1
    val TextoSuaveClaro = Color(0xFF525A64) //  7.6:1
    val AmbarOscuro = Color(0xFF8A4B00)     //  7.2:1 sobre fondo claro
    val PeligroClaro = Color(0xFFA32316)
}

/** Objetivos táctiles. 24dp es el mínimo legal; nosotros partimos de 48. */
object Tactil {
    val minimo = 48.dp
    val principal = 56.dp
    val separacion = 12.dp
}

object Espacio {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/**
 * Escala propia de la app, encima de la del sistema.
 *
 * El tope de 2.0 es deliberado: el sistema YA multiplica por su cuenta, y si
 * alguien tiene el teléfono al 200% y además elige "Muy grande" aquí, el
 * resultado sería 2.9x y la pantalla se rompe. WCAG pide soportar el 200%,
 * no el infinito.
 */
enum class EscalaTexto(val clave: String, val etiqueta: String, val factor: Float) {
    NORMAL("normal", "Normal", 1.0f),
    GRANDE("grande", "Grande", 1.2f),
    MUY_GRANDE("muyGrande", "Muy grande", 1.45f);

    companion object {
        fun desde(clave: String?) = entries.firstOrNull { it.clave == clave } ?: NORMAL
    }
}

val LocalEscala = compositionLocalOf { 1.0f }

private fun tipografia(factor: Float) = Typography(
    displayLarge = TextStyle(fontSize = (28 * factor).sp, lineHeight = (34 * factor).sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = (21 * factor).sp, lineHeight = (27 * factor).sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = (18 * factor).sp, lineHeight = (26 * factor).sp),
    bodyMedium = TextStyle(fontSize = (18 * factor).sp, lineHeight = (26 * factor).sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = (19 * factor).sp, lineHeight = (24 * factor).sp, fontWeight = FontWeight.SemiBold),
    bodySmall = TextStyle(fontSize = (15 * factor).sp, lineHeight = (21 * factor).sp)
)

private val esquemaOscuro = darkColorScheme(
    primary = Colores.Ambar,
    onPrimary = Colores.AmbarTexto,
    background = Colores.Carbon,
    onBackground = Colores.Texto,
    surface = Colores.Superficie,
    onSurface = Colores.Texto,
    surfaceVariant = Colores.SuperficieAlta,
    onSurfaceVariant = Colores.TextoSuave,
    outline = Colores.Borde,
    error = Colores.Peligro
)

private val esquemaClaro = lightColorScheme(
    primary = Colores.AmbarOscuro,
    onPrimary = Color.White,
    background = Colores.FondoClaro,
    onBackground = Colores.TextoClaro,
    surface = Colores.SuperficieClara,
    onSurface = Colores.TextoClaro,
    surfaceVariant = Color(0xFFF0F1F3),
    onSurfaceVariant = Colores.TextoSuaveClaro,
    outline = Colores.BordeClaro,
    error = Colores.PeligroClaro
)

@Composable
fun TemaRelay(
    preferencia: String = "sistema",
    escala: EscalaTexto = EscalaTexto.NORMAL,
    content: @Composable () -> Unit
) {
    val oscuro = when (preferencia) {
        "claro" -> false
        "oscuro" -> true
        else -> isSystemInDarkTheme()
    }

    // Combinamos nuestra escala con la del sistema y ponemos el tope.
    val escalaSistema = LocalDensity.current.fontScale
    val total = min(escalaSistema * escala.factor, 2.0f)
    val nuestroFactor = total / escalaSistema

    CompositionLocalProvider(LocalEscala provides nuestroFactor) {
        MaterialTheme(
            colorScheme = if (oscuro) esquemaOscuro else esquemaClaro,
            typography = tipografia(nuestroFactor),
            content = content
        )
    }
}
