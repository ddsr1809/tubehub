package com.tuempresa.creatorhub.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.tuempresa.creatorhub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente del servidor Relé.
 *
 * Sustituye a las Cloud Functions callable. La diferencia práctica es que
 * ahora nosotros somos responsables de adjuntar el token: el SDK de Functions
 * lo hacía por debajo.
 *
 * Pedimos el token en cada llamada en lugar de guardarlo. Caduca cada hora y
 * el SDK lo refresca solo; con `getIdToken(false)` lo devuelve desde caché
 * mientras siga vigente, así que no cuesta nada y nunca mandamos uno caducado.
 */
object ApiRelay {

    private const val TAG = "ApiRelay"

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private suspend fun token(): String {
        val usuario = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("No hay sesión activa.")
        return usuario.getIdToken(false).await().token
            ?: throw IllegalStateException("No se pudo obtener el token de sesión.")
    }

    /**
     * Ejecuta la petición y devuelve el cuerpo como texto.
     *
     * Si el servidor responde error, extraemos el campo `message`, que el
     * backend escribe en lenguaje llano precisamente para poder mostrarlo tal
     * cual al usuario. Un "error 400" pelado no le sirve a nadie.
     */
    private suspend fun ejecutar(peticion: Request.Builder): String =
        withContext(Dispatchers.IO) {
            val respuesta = cliente.newCall(
                peticion.header("Authorization", "Bearer ${token()}").build()
            ).execute()

            respuesta.use {
                val cuerpo = it.body?.string().orEmpty()

                if (!it.isSuccessful) {
                    val motivo = runCatching {
                        JSONObject(cuerpo).optString("message").takeIf { m -> m.isNotBlank() }
                    }.getOrNull()

                    Log.w(TAG, "Respuesta ${it.code} de ${it.request.url}")
                    throw IOException(motivo ?: "No se pudo completar la operación.")
                }

                cuerpo
            }
        }

    /** Reporta un enlace roto. Alimenta la redirección de emergencia. */
    suspend fun reportarEnlace(videoId: String?, creatorId: String?, motivo: String) {
        val cuerpo = JSONObject()
            .put("videoId", videoId ?: JSONObject.NULL)
            .put("creatorId", creatorId ?: JSONObject.NULL)
            .put("reason", motivo)

        ejecutar(
            Request.Builder()
                .url("${BuildConfig.API_BASE}/api/reportes")
                .post(cuerpo.toString().toRequestBody(JSON))
        )
    }

    /**
     * Borrado definitivo de la cuenta.
     * El servidor revoca el vínculo federado y elimina todo rastro.
     */
    suspend fun borrarCuenta() {
        ejecutar(
            Request.Builder()
                .url("${BuildConfig.API_BASE}/api/cuenta")
                .delete()
        )
    }
}
