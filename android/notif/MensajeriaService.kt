package com.tuempresa.creatorhub.notif

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tuempresa.creatorhub.data.ApiRelay
import com.tuempresa.creatorhub.data.DirectorioRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * El servidor envia siempre un bloque `notification`, asi que con la app en
 * segundo plano el sistema dibuja el aviso solo y este servicio ni se entera.
 * onMessageReceived solo se invoca con la app en primer plano.
 *
 * Aprovechamos ese caso para no molestar: si el usuario ya esta mirando la
 * pantalla, la lista se refresca en el siguiente ciclo de sondeo. Sacarle
 * ademas una notificacion encima seria ruido.
 */
class MensajeriaService : FirebaseMessagingService() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(mensaje: RemoteMessage) {
        Log.d(TAG, "Aviso en primer plano: ${mensaje.data["videoId"]}")
    }

    /**
     * El token se regenera al restaurar el telefono desde una copia de
     * seguridad, al borrar los datos de la app o tras mucho tiempo sin uso.
     * Cuando eso pasa, las suscripciones a topics anteriores se pierden y el
     * usuario deja de recibir avisos sin motivo aparente.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "Token de FCM renovado")

        alcance.launch {
            runCatching {
                // Sin sesion no hay a quien preguntar. Pasa si el token se
                // renueva antes de que la app haya llegado a arrancar.
                if (!ApiRelay.haySesion) {
                    Log.d(TAG, "Sin sesión todavía; las suscripciones se rehacen al abrir la app")
                    return@runCatching
                }

                val favoritos = ApiRelay.perfil().favoritos
                favoritos.forEach { id ->
                    FirebaseMessaging.getInstance()
                        .subscribeToTopic(DirectorioRepo.topicDe(id)).await()
                }
                Log.d(TAG, "Suscripciones rehechas: ${favoritos.size}")

            }.onFailure {
                Log.w(TAG, "No se pudieron rehacer las suscripciones", it)
            }
        }
    }

    companion object {
        private const val TAG = "MensajeriaService"
    }
}
