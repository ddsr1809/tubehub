package com.tuempresa.creatorhub.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tuempresa.creatorhub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cliente del servidor Relé.
 *
 * Sustituye por completo a Firestore y Firebase Auth. La sesión es un JWT que
 * emite nuestro servidor y que guardamos aquí; no tiene nada que ver con
 * Firebase, que ahora solo interviene en las notificaciones push.
 *
 * El identificador de dispositivo se genera una vez y sobrevive a los cierres
 * de sesión: es lo que permite recuperar la cuenta anónima al reabrir la app
 * sin pedirle nada al usuario.
 */
object ApiRelay {

    private const val TAG = "ApiRelay"
    private const val PREFS = "relay_sesion"
    private const val CLAVE_TOKEN = "token"
    private const val CLAVE_DISPOSITIVO = "device_id"

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private lateinit var prefs: SharedPreferences

    /** Se llama una vez desde RelayApp.onCreate(). */
    fun inicializar(contexto: Context) {
        prefs = contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Identificador estable de esta instalación.
     *
     * No usamos ANDROID_ID ni nada ligado al hardware: un UUID nuestro es
     * suficiente para reconocer la instalación y no identifica a la persona
     * fuera de esta app.
     */
    val deviceId: String
        get() = prefs.getString(CLAVE_DISPOSITIVO, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(CLAVE_DISPOSITIVO, it).apply()
        }

    var token: String?
        get() = prefs.getString(CLAVE_TOKEN, null)
        private set(valor) {
            prefs.edit().apply {
                if (valor == null) remove(CLAVE_TOKEN) else putString(CLAVE_TOKEN, valor)
            }.apply()
        }

    val haySesion: Boolean get() = !token.isNullOrBlank()

    data class Sesion(
        val usuarioId: String,
        val proveedor: String,
        val email: String?,
        val esAdmin: Boolean,
        val favoritosFusionados: Boolean
    )

    /** Datos de la sesión actual, sin tocar la red. */
    var sesion: Sesion? = null
        private set

    // -------------------------------------------------------------------------
    // Sesión
    // -------------------------------------------------------------------------

    /** Sesión invisible al abrir la app. Sin formularios ni contraseñas. */
    suspend fun entrarAnonimo(): Sesion =
        guardarSesion(post("/api/auth/anonimo", JSONObject().put("deviceId", deviceId), conToken = false))

    /**
     * Enlaza con Google.
     *
     * Mandamos el idToken que devuelve Credential Manager; el servidor lo
     * verifica contra las claves públicas de Google y decide si es una cuenta
     * nueva o una que ya existía en otro teléfono. La fusión de favoritos la
     * resuelve él, por eso aquí no hay lógica de colisiones.
     */
    suspend fun entrarConGoogle(idToken: String): Sesion =
        guardarSesion(post("/api/auth/google",
            JSONObject().put("token", idToken).put("deviceId", deviceId), conToken = false))

    /** Token nuevo antes de que caduque. La app lo llama al arrancar. */
    suspend fun renovar(): Sesion? = runCatching {
        guardarSesion(post("/api/auth/renovar", null))
    }.getOrNull()

    fun cerrarSesion() {
        token = null
        sesion = null
    }

    private fun guardarSesion(json: JSONObject): Sesion {
        token = json.getString("token")
        return Sesion(
            usuarioId = json.getString("usuarioId"),
            proveedor = json.optString("proveedor", "anonimo"),
            email = json.optStringONull("email"),
            esAdmin = json.optBoolean("esAdmin", false),
            favoritosFusionados = json.optBoolean("favoritosFusionados", false)
        ).also { sesion = it }
    }

    // -------------------------------------------------------------------------
    // Directorio
    // -------------------------------------------------------------------------

    suspend fun creadores(categoria: String? = null): List<Creador> {
        val ruta = if (categoria.isNullOrBlank() || categoria == "todos") "/api/creadores"
                   else "/api/creadores?categoria=$categoria"

        return getArray(ruta).mapJson { creadorDe(it) }
    }

    suspend fun publicaciones(limite: Int = 50): List<Publicacion> =
        getArray("/api/publicaciones?limite=$limite").mapJson { publicacionDe(it) }

    suspend fun perfil(): Perfil {
        val json = getObject("/api/perfil")
        return Perfil(
            favoritos = json.optJSONArray("favoritos").mapJsonStrings(),
            escalaTexto = json.optString("escalaTexto", "normal"),
            tema = json.optString("tema", "sistema"),
            avisos = json.optBoolean("avisos", true)
        )
    }

    suspend fun seguir(creadorId: String) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/favoritos/$creadorId")
            .put(vacio()))
    }

    suspend fun dejarDeSeguir(creadorId: String) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/favoritos/$creadorId")
            .delete())
    }

    suspend fun guardarPreferencia(clave: String, valor: Any) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/preferencias")
            .put(JSONObject().put(clave, valor).toString().toRequestBody(JSON)))
    }

    suspend fun reportarEnlace(videoId: String?, creatorId: String?, motivo: String) {
        post("/api/reportes", JSONObject()
            .put("videoId", videoId ?: JSONObject.NULL)
            .put("creadorId", creatorId ?: JSONObject.NULL)
            .put("motivo", motivo))
    }

    suspend fun borrarCuenta() {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/cuenta")
            .delete())
        cerrarSesion()
    }

    // -------------------------------------------------------------------------
    // Mapeo
    // -------------------------------------------------------------------------
    // El servidor usa nombres en español; los modelos de la app conservan los
    // suyos para no tocar ni una línea de la interfaz. La traducción vive aquí,
    // en un solo sitio.

    private fun creadorDe(json: JSONObject): Creador {
        val conexiones = mutableMapOf<String, Conexion>()
        json.optJSONArray("conexiones")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                conexiones[c.getString("plataforma")] = Conexion(
                    url = c.optString("url", ""),
                    handle = c.optStringONull("handle"),
                    channelId = c.optStringONull("channelId")
                )
            }
        }

        return Creador(
            id = json.getString("id"),
            name = json.optString("nombre", ""),
            category = json.optString("categoria", "otros"),
            bio = json.optStringONull("bio"),
            photoUrl = json.optStringONull("fotoUrl"),
            platforms = conexiones,
            active = true
        )
    }

    private fun publicacionDe(json: JSONObject): Publicacion {
        val videoId = json.optString("videoId", "")
        return Publicacion(
            id = videoId,
            videoId = videoId,
            creatorId = json.optString("creadorId", ""),
            creatorName = json.optStringONull("creadorNombre"),
            platform = json.optString("plataforma", "youtube"),
            title = json.optString("titulo", "Video nuevo"),
            thumbnailUrl = json.optStringONull("miniaturaUrl"),
            url = json.optStringONull("url"),
            publishedAt = json.optStringONull("publicadoEn")?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            },
            status = json.optString("estado", "ok"),
            overrideUrl = json.optStringONull("destinoUrl"),
            overridePlatform = json.optStringONull("destinoPlataforma"),
            esEnVivo = json.optBoolean("enVivo", false),
            tipo = json.optString("tipo", "video")
        )
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private suspend fun getObject(ruta: String): JSONObject =
        JSONObject(ejecutar(Request.Builder().url(BuildConfig.API_BASE + ruta).get()))

    private suspend fun getArray(ruta: String): JSONArray =
        JSONArray(ejecutar(Request.Builder().url(BuildConfig.API_BASE + ruta).get()))

    private suspend fun post(ruta: String, cuerpo: JSONObject?, conToken: Boolean = true): JSONObject {
        val peticion = Request.Builder()
            .url(BuildConfig.API_BASE + ruta)
            .post(cuerpo?.toString()?.toRequestBody(JSON) ?: vacio())

        val respuesta = ejecutar(peticion, conToken)
        return if (respuesta.isBlank()) JSONObject() else JSONObject(respuesta)
    }

    private fun vacio(): RequestBody = ByteArray(0).toRequestBody(JSON)

    private suspend fun ejecutar(peticion: Request.Builder, conToken: Boolean = true): String =
        withContext(Dispatchers.IO) {
            if (conToken) {
                val actual = token ?: throw IOException("No hay sesión activa.")
                peticion.header("Authorization", "Bearer $actual")
            }

            cliente.newCall(peticion.build()).execute().use { respuesta ->
                val cuerpo = respuesta.body?.string().orEmpty()

                if (!respuesta.isSuccessful) {
                    // Un 401 significa que el token caducó o que la cuenta ya
                    // no existe. Lo borramos para que el siguiente arranque
                    // cree una sesión limpia en vez de reintentar en bucle.
                    if (respuesta.code == 401) cerrarSesion()

                    val motivo = runCatching {
                        JSONObject(cuerpo).optString("message").takeIf { it.isNotBlank() }
                    }.getOrNull()

                    Log.w(TAG, "Respuesta ${respuesta.code} de ${respuesta.request.url}")
                    throw IOException(motivo ?: "No se pudo completar la operación.")
                }

                cuerpo
            }
        }
}

// --- Utilidades de JSON ------------------------------------------------------
// org.json devuelve la cadena "null" en vez de null cuando el campo viene nulo,
// que es una fuente clásica de textos con "null" impreso en la pantalla.

internal fun JSONObject.optStringONull(clave: String): String? =
    if (isNull(clave)) null else optString(clave).takeIf { it.isNotBlank() }

internal fun <T> JSONArray.mapJson(transformar: (JSONObject) -> T): List<T> =
    (0 until length()).map { transformar(getJSONObject(it)) }

internal fun JSONArray?.mapJsonStrings(): List<String> =
    this?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
