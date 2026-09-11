package com.tuempresa.creatorhub.notif

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * El backend envía siempre un bloque `notification`, así que cuando la app está
 * en segundo plano el sistema dibuja el aviso solo y este servicio ni se entera.
 * onMessageReceived únicamente se invoca con la app en primer plano.
 *
 * Aprovechamos ese caso para no molestar: si el usuario ya está mirando la
 * pantalla, la lista se actualiza sola por el listener de Firestore. Sacarle
 * además una notificación encima sería ruido.
 */
class MensajeriaService : FirebaseMessagingService() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(mensaje: RemoteMessage) {
        Log.d(TAG, "Aviso en primer plano: ${mensaje.data["videoId"]}")
        // Sin acción. El Flow de Firestore ya refresca la lista de novedades.
    }

    /**
     * El token se regenera al restaurar el teléfono desde una copia de
     * seguridad, al borrar los datos de la app o tras mucho tiempo sin uso.
     * Cuando eso pasa, las suscripciones a topics anteriores se pierden y hay
     * que rehacerlas o el usuario deja de recibir avisos sin motivo aparente.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "Token de FCM renovado")

        alcance.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            runCatching {
                val doc = FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()

                @Suppress("UNCHECKED_CAST")
                val favoritos = (doc.get("favoritos") as? List<String>).orEmpty()

                favoritos.forEach { id ->
                    FirebaseMessaging.getInstance().subscribeToTopic("creator_$id").await()
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
