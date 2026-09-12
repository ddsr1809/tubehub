package com.tuempresa.creatorhub.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import android.util.Log

/**
 * Toda la lectura del directorio pasa por aquí.
 *
 * El directorio es de solo lectura para la app: las Firestore Security Rules
 * bloquean cualquier escritura sobre /creators y /videos. Lo único que el
 * usuario modifica es su propio documento en /users.
 */
class DirectorioRepo(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val mensajeria: FirebaseMessaging = FirebaseMessaging.getInstance()
) {

    /** El directorio completo. Son pocos documentos y cambian poco. */
    fun creadores(): Flow<List<Creador>> = callbackFlow {
        val registro = db.collection("creators")
            .whereEqualTo("active", true)
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w(TAG, "Error leyendo el directorio", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val lista = snap?.documents.orEmpty().mapNotNull { doc ->
                    doc.toObject(Creador::class.java)?.copy(id = doc.id)
                }
                trySend(lista)
            }
        awaitClose { registro.remove() }
    }

    /** Perfil del usuario: favoritos y preferencias. */
    fun perfil(): Flow<Perfil> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(Perfil())
            awaitClose { }
            return@callbackFlow
        }
        val registro = db.collection("users").document(uid)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.w(TAG, "Error leyendo el perfil", error)
                    return@addSnapshotListener
                }
                trySend(doc?.toObject(Perfil::class.java) ?: Perfil())
            }
        awaitClose { registro.remove() }
    }

    /**
     * Publicaciones de los creadores que sigue el usuario.
     *
     * Firestore admite como máximo 30 valores en un whereIn, así que partimos
     * en lotes y unimos los resultados en memoria. Con más de un centenar de
     * favoritos convendría invertir el modelo y escribir un feed por usuario
     * desde el backend, pero para este tamaño esto es más simple y más barato.
     */
    fun publicaciones(favoritos: List<String>): Flow<List<Publicacion>> = callbackFlow {
        if (favoritos.isEmpty()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val acumulado = LinkedHashMap<String, Publicacion>()
        val registros = favoritos.chunked(30).map { lote ->
            db.collection("videos")
                .whereIn("creatorId", lote)
                .orderBy("publishedAt", Query.Direction.DESCENDING)
                .limit(30)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        Log.w(TAG, "Error leyendo publicaciones", error)
                        return@addSnapshotListener
                    }
                    snap?.documents.orEmpty().forEach { doc ->
                        doc.toObject(Publicacion::class.java)?.let {
                            acumulado[doc.id] = it.copy(id = doc.id)
                        }
                    }
                    val lista = acumulado.values
                        .filter { it.status != "removed" }
                        .sortedByDescending { it.publishedAt?.seconds ?: 0L }
                        .take(50)
                    trySend(lista)
                }
        }
        awaitClose { registros.forEach { it.remove() } }
    }

    /**
     * Seguir o dejar de seguir.
     *
     * Dos cosas a la vez: el favorito en Firestore, que sobrevive al cambio de
     * teléfono, y el topic de FCM, que es lo que hace llegar el aviso a ESTE
     * aparato. Si solo hiciéramos lo primero, el usuario vería el creador
     * marcado pero no recibiría nada.
     */
    suspend fun alternarFavorito(creatorId: String, siguiendoAhora: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection("users").document(uid)

        if (siguiendoAhora) {
            ref.set(mapOf("favoritos" to FieldValue.arrayRemove(creatorId)), com.google.firebase.firestore.SetOptions.merge()).await()
            runCatching { mensajeria.unsubscribeFromTopic(topicDe(creatorId)).await() }
        } else {
            ref.set(mapOf("favoritos" to FieldValue.arrayUnion(creatorId)), com.google.firebase.firestore.SetOptions.merge()).await()
            runCatching { mensajeria.subscribeToTopic(topicDe(creatorId)).await() }
        }
    }

    /**
     * Vuelve a alinear los topics tras iniciar sesión en otro teléfono.
     * Los favoritos viven en Firestore, pero los topics son por dispositivo:
     * un teléfono nuevo no está suscrito a nada aunque la cuenta sí lo esté.
     */
    suspend fun sincronizarTopics(favoritos: List<String>) {
        favoritos.forEach { id ->
            runCatching { mensajeria.subscribeToTopic(topicDe(id)).await() }
        }
    }

    suspend fun guardarPreferencia(clave: String, valor: Any) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid)
            .set(mapOf(clave to valor), com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    /** Reporta un enlace roto. Alimenta la redirección de emergencia. */
    suspend fun reportarEnlaceRoto(videoId: String?, creatorId: String?, motivo: String = "enlace_roto") {
        ApiRelay.reportarEnlace(videoId, creatorId, motivo)
    }

    companion object {
        private const val TAG = "DirectorioRepo"
        fun topicDe(creatorId: String) = "creator_$creatorId"
    }
}
