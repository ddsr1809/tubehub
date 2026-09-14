package com.tuempresa.creatorhub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tuempresa.creatorhub.data.ApiRelay


/**
 * Arranque de la aplicacion.
 *
 * Dos cosas que tienen que ocurrir antes que nada: preparar el cliente del
 * servidor (necesita un Context para guardar el token y el identificador de
 * dispositivo) y crear los canales de notificacion.
 *
 * Crear un canal es idempotente: si ya existe, Android ignora la llamada. Lo
 * que NO puede cambiarse despues es la importancia; si el usuario la baja a
 * mano, se respeta su decision y ningun codigo puede subirla de vuelta.
 *
 * Separamos publicaciones de avisos a proposito. Alguien puede querer silenciar
 * los videos nuevos durante unas vacaciones sin perderse que un video cambio
 * de plataforma. Un solo canal obligaria a elegir todo o nada.
 */
class RelayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ApiRelay.inicializar(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            crearCanales()
        }
    }

    private fun crearCanales() {
        val gestor = getSystemService(NotificationManager::class.java)

        val publicaciones = NotificationChannel(
            CANAL_PUBLICACIONES,
            getString(R.string.canal_publicaciones),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.canal_publicaciones_desc)
            enableVibration(true)
            setShowBadge(true)
        }

        val avisos = NotificationChannel(
            CANAL_AVISOS,
            getString(R.string.canal_avisos),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.canal_avisos_desc)
            enableVibration(true)
        }

        gestor.createNotificationChannels(listOf(publicaciones, avisos))
    }

    companion object {
        // Estos identificadores tienen que coincidir letra por letra con los
        // que envia el servidor en PushService.java. Si no coinciden, el aviso
        // llega pero cae en un canal generico llamado "Otros".
        const val CANAL_PUBLICACIONES = "publicaciones"
        const val CANAL_AVISOS = "avisos"
    }
}
