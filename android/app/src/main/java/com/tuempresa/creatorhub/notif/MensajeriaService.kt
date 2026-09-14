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
 * Servicio encargado de recibir mensajes de Firebase Cloud Messaging.
 *
 * La autenticación, el perfil y los favoritos ahora se consultan
 * directamente en el servidor mediante ApiRelay.
 */
class MensajeriaService : FirebaseMessagingService() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(mensaje: RemoteMessage) {
        Log.d(TAG, "Aviso en primer plano: ${mensaje.data["videoId"]}")
    }

    /**
     * Cuando FCM renueva el token, se reconstruyen las suscripciones
     * a los creadores favoritos de la cuenta actual.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "Token de FCM renovado")

        alcance.launch {
            runCatching {
                if (!ApiRelay.haySesion) {
                    Log.d(
                        TAG,
                        "Sin sesión todavía; las suscripciones se rehacen al abrir la app"
                    )
                    return@runCatching
                }

                val favoritos = ApiRelay.perfil().favoritos

                favoritos.forEach { creatorId ->
                    FirebaseMessaging.getInstance()
                        .subscribeToTopic(
                            DirectorioRepo.topicDe(creatorId)
                        )
                        .await()
                }

                Log.d(TAG, "Suscripciones rehechas: ${favoritos.size}")
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "No se pudieron rehacer las suscripciones",
                    error
                )
            }
        }
    }

    companion object {
        private const val TAG = "MensajeriaService"
    }
}