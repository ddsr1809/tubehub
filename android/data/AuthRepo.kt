package com.tuempresa.creatorhub.data

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.tuempresa.creatorhub.BuildConfig

/**
 * Identidad.
 *
 * Principio de diseño intacto: nadie ve una pantalla de registro al abrir la
 * app. Una sola equivocación en el teclado durante el alta basta para que una
 * persona mayor abandone el producto. Entramos con una sesión anónima ligada
 * al dispositivo y solo ofrecemos "guardar mi cuenta" cuando ya hay favoritos
 * que valga la pena conservar.
 *
 * Lo que cambió: la sesión la emite nuestro servidor, no Firebase. Credential
 * Manager sigue siendo quien saca el token de Google del teléfono, pero ese
 * token va al servidor, que lo verifica contra las claves públicas de Google.
 *
 * La resolución de colisiones también se mudó al servidor. Si esa cuenta de
 * Google ya existía desde otro teléfono, él detecta el caso y fusiona los
 * favoritos; aquí solo miramos el campo `favoritosFusionados` de la respuesta.
 */
class AuthRepo {

    /** Resultado de intentar guardar la cuenta, en términos que la UI entiende. */
    sealed interface Resultado {
        data object Vinculada : Resultado
        data class Recuperada(val favoritosFusionados: Int) : Resultado

        /**
         * Ya no lo emitimos: el servidor resuelve la fusión solo. Se mantiene
         * en la jerarquía para no romper el `when` exhaustivo del ViewModel.
         */
        data class Conflicto(val correo: String?, val credencialPendiente: String) : Resultado

        data object Cancelada : Resultado
        data class Fallo(val mensaje: String) : Resultado
    }

    val esAnonimo: Boolean
        get() = ApiRelay.sesion?.proveedor?.let { it == "anonimo" } ?: true

    /** Compatibilidad con el ViewModel, que leía `usuario?.email`. */
    val usuario: Usuario?
        get() = ApiRelay.sesion?.let { Usuario(it.usuarioId, it.email) }

    data class Usuario(val uid: String, val email: String?)

    /** Se llama al arrancar. Silencioso, sin interfaz. */
    suspend fun iniciarSesionInvisible() {
        // Si ya hay token guardado, basta con renovarlo. Solo creamos cuenta
        // nueva si no había ninguna o si el servidor rechazó la anterior.
        if (ApiRelay.haySesion && ApiRelay.renovar() != null) return
        ApiRelay.entrarAnonimo()
    }

    // -------------------------------------------------------------------------
    // Google mediante Credential Manager
    // -------------------------------------------------------------------------
    // GoogleSignInClient está obsoleto. Mucho tutorial sigue mostrándolo; esta
    // es la API vigente y la única que Google mantiene.

    private suspend fun idTokenDeGoogle(contexto: Context): String {
        val gestor = CredentialManager.create(contexto)

        val opcion = GetGoogleIdOption.Builder()
            // false para que aparezcan también las cuentas que nunca han usado
            // esta app. Con true, un usuario nuevo vería un diálogo vacío y
            // pensaría que algo se rompió.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()

        val peticion = GetCredentialRequest.Builder().addCredentialOption(opcion).build()
        val credencial = gestor.getCredential(contexto, peticion).credential

        if (credencial !is CustomCredential ||
            credencial.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Google devolvió un tipo de credencial inesperado.")
        }

        return GoogleIdTokenCredential.createFrom(credencial.data).idToken
    }

    suspend fun vincularConGoogle(contexto: Context): Resultado {
        val idToken = try {
            idTokenDeGoogle(contexto)
        } catch (e: GetCredentialCancellationException) {
            return Resultado.Cancelada
        } catch (e: NoCredentialException) {
            return Resultado.Fallo("No hay ninguna cuenta de Google configurada en este teléfono.")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager falló", e)
            return Resultado.Fallo("No se pudo abrir el selector de cuentas de Google.")
        }

        return try {
            val sesion = ApiRelay.entrarConGoogle(idToken)

            if (sesion.favoritosFusionados) {
                // El servidor encontró una cuenta previa y juntó los favoritos.
                // No sabemos cuántos exactamente, así que dejamos el contador
                // en cero y el mensaje se redacta en genérico.
                Resultado.Recuperada(0)
            } else {
                Resultado.Vinculada
            }

        } catch (e: Exception) {
            Log.w(TAG, "Vinculación fallida", e)
            Resultado.Fallo(e.message ?: "No se pudo guardar la cuenta.")
        }
    }

    /**
     * Quedó sin uso: el servidor fusiona las cuentas por su cuenta. Se
     * conserva para que el ViewModel siga compilando sin cambios.
     */
    suspend fun resolverConflicto(contexto: Context, credencialPendiente: String): Resultado =
        vincularConGoogle(contexto)

    // -------------------------------------------------------------------------
    // Cierre y borrado
    // -------------------------------------------------------------------------

    suspend fun cerrarSesion(contexto: Context) {
        runCatching {
            CredentialManager.create(contexto)
                .clearCredentialState(ClearCredentialStateRequest())
        }
        ApiRelay.cerrarSesion()
        iniciarSesionInvisible() // la app nunca se queda sin sesión
    }

    /**
     * Borrado definitivo desde dentro de la app, como exige la Guideline
     * 5.1.1(v). El servidor revoca el vínculo federado y elimina todo rastro.
     */
    suspend fun borrarCuenta(contexto: Context) {
        ApiRelay.borrarCuenta()
        cerrarSesion(contexto)
    }

    companion object {
        private const val TAG = "AuthRepo"
    }
}
