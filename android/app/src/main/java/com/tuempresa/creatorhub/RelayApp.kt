package com.tuempresa.creatorhub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Los canales se crean aquí, una sola vez, al arrancar el proceso.
 *
 * Crear un canal es idempotente: si ya existe, Android ignora la llamada.
 * Lo que NO puede cambiarse después es la importancia; si el usuario la baja
 * a mano, se respeta su decisión y ningún código puede subirla de vuelta.
 *
 * Separamos publicaciones de avisos a propósito. Alguien puede querer silenciar
 * los videos nuevos durante unas vacaciones sin perderse que un video cambió
 * de plataforma. Un solo canal obligaría a elegir todo o nada.
 */
class RelayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) crearCanales()
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
        // que envía el backend en functions/src/push.js. Si no coinciden,
        // el aviso llega pero cae en un canal genérico llamado "Otros".
        const val CANAL_PUBLICACIONES = "publicaciones"
        const val CANAL_AVISOS = "avisos"
    }
}
