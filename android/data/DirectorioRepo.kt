package com.tuempresa.creatorhub.data

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

/**
 * Acceso al directorio, ahora contra nuestro servidor.
 *
 * Las firmas públicas son las mismas que cuando esto hablaba con Firestore, de
 * modo que AppViewModel no cambia. Lo que sí cambió es el mecanismo: antes
 * Firestore empujaba los cambios en vivo; ahora preguntamos.
 *
 * En la práctica se nota poco. La novedad importante llega por notificación
 * push, y el feed no es una pantalla que la gente mire fijamente esperando que
 * cambie. El sondeo es de cortesía, con intervalos amplios.
 */
class DirectorioRepo(
    private val mensajeria: FirebaseMessaging = FirebaseMessaging.getInstance()
) {

    /**
     * El directorio completo.
     *
     * Cambia muy poco —lo edita una persona a mano—, así que refrescar cada
     * cinco minutos sobra. El Flow emite de inmediato al suscribirse, que es
     * lo que importa para que la pantalla pinte rápido.
     */
    fun creadores(): Flow<List<Creador>> = sondear(intervaloMs = 5 * 60_000L) {
        ApiRelay.creadores()
    }

    fun perfil(): Flow<Perfil> = sondear(intervaloMs = 60_000L) {
        ApiRelay.perfil()
    }

    /**
     * El parámetro `favoritos` ya no se usa para filtrar: el servidor sabe a
     * quién sigue el usuario por el token. Se mantiene en la firma porque
     * AppViewModel lo usa como disparador para reemitir cuando cambian.
     */
    fun publicaciones(favoritos: List<String>): Flow<List<Publicacion>> =
        if (favoritos.isEmpty()) flow { emit(emptyList()) }
        else sondear(intervaloMs = 2 * 60_000L) { ApiRelay.publicaciones() }

    /**
     * Seguir o dejar de seguir.
     *
     * Dos cosas a la vez: el favorito en el servidor, que sobrevive al cambio
     * de teléfono, y el topic de FCM, que es lo que hace llegar el aviso a
     * ESTE aparato. Con solo lo primero, el usuario vería el creador marcado
     * y no recibiría nada.
     */
    suspend fun alternarFavorito(creatorId: String, siguiendoAhora: Boolean) {
        if (siguiendoAhora) {
            ApiRelay.dejarDeSeguir(creatorId)
            runCatching { mensajeria.unsubscribeFromTopic(topicDe(creatorId)).await() }
        } else {
            ApiRelay.seguir(creatorId)
            runCatching { mensajeria.subscribeToTopic(topicDe(creatorId)).await() }
        }
    }

    /**
     * Vuelve a alinear los topics tras iniciar sesión en otro teléfono.
     * Los favoritos viven en la cuenta, pero los topics son por dispositivo:
     * un teléfono nuevo no está suscrito a nada aunque la cuenta sí lo esté.
     */
    suspend fun sincronizarTopics(favoritos: List<String>) {
        favoritos.forEach { id ->
            runCatching { mensajeria.subscribeToTopic(topicDe(id)).await() }
        }
    }

    suspend fun guardarPreferencia(clave: String, valor: Any) {
        ApiRelay.guardarPreferencia(clave, valor)
    }

    /** Reporta un enlace roto. Alimenta la redirección de emergencia. */
    suspend fun reportarEnlaceRoto(
        videoId: String?,
        creatorId: String?,
        motivo: String = "enlace_roto"
    ) {
        ApiRelay.reportarEnlace(videoId, creatorId, motivo)
    }

    /**
     * Emite una vez al suscribirse y después cada `intervaloMs`.
     *
     * Un fallo de red no corta el Flow: se registra y se reintenta en el
     * siguiente ciclo. Si cortáramos, la pantalla se quedaría congelada hasta
     * que el usuario saliera y volviera a entrar.
     */
    private fun <T> sondear(intervaloMs: Long, traer: suspend () -> T): Flow<T> = flow {
        while (true) {
            runCatching { traer() }
                .onSuccess { emit(it) }
                .onFailure { Log.w(TAG, "Fallo al consultar el servidor: ${it.message}") }
            delay(intervaloMs)
        }
    }

    companion object {
        private const val TAG = "DirectorioRepo"
        fun topicDe(creatorId: String) = "creator_$creatorId"
    }
}
