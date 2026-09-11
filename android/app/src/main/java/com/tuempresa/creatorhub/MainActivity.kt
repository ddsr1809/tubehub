package com.tuempresa.creatorhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.tuempresa.creatorhub.enlaces.Enrutador
import com.tuempresa.creatorhub.ui.*

class MainActivity : ComponentActivity() {

    // El permiso se pide en cuanto arranca la app porque sin él el producto no
    // hace nada útil. Si el usuario dice que no, la app sigue funcionando como
    // directorio; simplemente no avisa.
    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* concedido o no, seguimos igual */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        solicitarPermisoDeAvisos()

        setContent {
            val modelo: AppViewModel = viewModel()
            val estado by modelo.estado.collectAsState()

            TemaRelay(
                preferencia = estado.perfil.tema,
                escala = EscalaTexto.desde(estado.perfil.escalaTexto)
            ) {
                if (!estado.listo) {
                    PantallaDeCarga()
                } else {
                    Navegacion(modelo, estado)
                }
            }

            // La notificación que abrió la app trae el destino en los extras.
            // Lo procesamos una vez y lo limpiamos, o al girar la pantalla
            // volvería a abrirse el video.
            LaunchedEffect(Unit) { procesarIntent(intent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntent(intent)
    }

    /**
     * FCM mete los pares de `data` como extras del Intent cuando el usuario
     * toca la notificación. Un toque, y el video ya está abriéndose en su app
     * oficial: cero pantallas intermedias.
     */
    private fun procesarIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val plataforma = extras.getString("platform") ?: return

        val videoId = extras.getString("videoId")
        val url = extras.getString("url")
        val tipo = extras.getString("tipo")

        Enrutador.abrirVideo(
            contexto = this,
            plataforma = plataforma,
            videoId = videoId,
            url = url,
            campana = if (tipo == "movido") "contenido_movido" else "aviso_publicacion"
        )

        // Consumido: que no se repita al recrear la Activity.
        intent.replaceExtras(Bundle())
    }

    private fun solicitarPermisoDeAvisos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedido = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedido) pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun PantallaDeCarga() {
    Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                "Un momento…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.md)
            )
        }
    }
}

/**
 * Navegación anclada abajo.
 *
 * Los teléfonos actuales son demasiado altos para alcanzar la parte superior
 * con el pulgar sin recolocar la mano, y recolocar la mano es justo lo que
 * cuesta a quien tiene menos destreza. Todo lo que se toca vive en el tercio
 * inferior de la pantalla.
 */
@Composable
private fun Navegacion(modelo: AppViewModel, estado: EstadoApp) {
    val nav = rememberNavController()
    val contexto = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val destinos = listOf(
        "novedades" to "Novedades",
        "directorio" to "Directorio",
        "ajustes" to "Ajustes"
    )

    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            modelo.mensajeVisto()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val actual by nav.currentBackStackEntryAsState()
            val ruta = actual?.destination?.route

            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.heightIn(min = Tactil.principal + 28.dp)
            ) {
                destinos.forEach { (destino, etiqueta) ->
                    NavigationBarItem(
                        selected = ruta == destino,
                        onClick = {
                            nav.navigate(destino) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // Sin icono a propósito: una etiqueta escrita no hay
                        // que adivinarla, y a este público los pictogramas
                        // abstractos le cuestan más que una palabra.
                        icon = { },
                        label = { Text(etiqueta, style = MaterialTheme.typography.bodySmall) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { relleno ->
        NavHost(
            navController = nav,
            startDestination = "novedades",
            modifier = Modifier.padding(relleno)
        ) {
            composable("novedades") {
                NovedadesPantalla(
                    publicaciones = estado.publicaciones,
                    hayFavoritos = estado.perfil.favoritos.isNotEmpty(),
                    cuantosFavoritos = estado.perfil.favoritos.size,
                    onIrAlDirectorio = { nav.navigate("directorio") },
                    onReportar = { modelo.reportarEnlace(it.videoId, it.creatorId) }
                )
            }

            composable("directorio") {
                DirectorioPantalla(
                    creadores = estado.creadores,
                    sigue = modelo::sigue,
                    onSeguir = { modelo.alternarFavorito(it) },
                    onAbrirCreador = { nav.navigate("creador/$it") }
                )
            }

            composable(
                "creador/{creatorId}",
                arguments = listOf(navArgument("creatorId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("creatorId").orEmpty()
                CreadorPantalla(
                    creador = modelo.creador(id),
                    siguiendo = modelo.sigue(id),
                    onSeguir = { modelo.alternarFavorito(id) },
                    onVolver = { nav.popBackStack() }
                )
            }

            composable("ajustes") {
                AjustesPantalla(
                    estado = estado,
                    onGuardarPreferencia = modelo::guardarPreferencia,
                    onVincularGoogle = { modelo.vincularConGoogle(contexto) },
                    onCerrarSesion = { modelo.cerrarSesion(contexto) },
                    onBorrarCuenta = { modelo.borrarCuenta(contexto) }
                )
            }
        }
    }

    // Colisión de cuentas: en vez de un código de error, explicamos qué botón
    // tocar. Este diálogo es el que evita que alguien se quede fuera de su
    // propia cuenta al cambiar de teléfono.
    estado.conflicto?.let { conflicto ->
        AlertDialog(
            onDismissRequest = { modelo.descartarConflicto() },
            title = { Text("Ese correo ya tiene cuenta") },
            text = {
                Text(
                    "Tu correo ${conflicto.correo ?: ""} ya se registró antes. " +
                        "Entra otra vez con Google y juntamos las dos cuentas en una sola."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { modelo.resolverConflicto(contexto) },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text("Entrar con Google") }
            },
            dismissButton = {
                TextButton(
                    onClick = { modelo.descartarConflicto() },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text("Ahora no") }
            }
        )
    }
}
