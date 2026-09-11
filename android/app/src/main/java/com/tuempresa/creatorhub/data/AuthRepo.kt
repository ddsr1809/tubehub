package com.tuempresa.creatorhub.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import com.tuempresa.creatorhub.BuildConfig

/**
 * Identidad.
 *
 * Principio de diseño: nadie ve una pantalla de registro al abrir la app.
 * Una sola equivocación en el teclado durante el alta basta para que una
 * persona mayor abandone el producto. Entramos en modo anónimo y solo
 * ofrecemos "guardar mi cuenta" cuando ya hay favoritos que valga la pena
 * conservar.
 *
 * De paso cubre el criterio 3.3.8 de WCAG (Accessible Authentication): no
 * obligamos a nadie a recordar una contraseña.
 */
class AuthRepo(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val funciones: FirebaseFunctions = FirebaseFunctions.getInstance()
) {

    /** Resultado de intentar guardar la cuenta, en términos que la UI entiende. */
    sealed interface Resultado {
        data object Vinculada : Resultado
        data class Recuperada(val favoritosFusionados: Int) : Resultado
        data class Conflicto(val correo: String?, val credencialPendiente: AuthCredential) : Resultado
        data object Cancelada : Resultado
        data class Fallo(val mensaje: String) : Resultado
    }

    val usuario get() = auth.currentUser
    val esAnonimo get() = auth.currentUser?.isAnonymous ?: true

    /** Se llama al arrancar. Silencioso, sin interfaz. */
    suspend fun iniciarSesionInvisible() {
        if (auth.currentUser != null) return
        val resultado = auth.signInAnonymously().await()
        val uid = resultado.user?.uid ?: return

        // Ojo: aquí NO escribimos "favoritos" a lista vacía. Con merge, eso no
        // respeta el arreglo existente: lo reemplaza. Si la sesión se restaura
        // tarde, borraríamos los creadores del usuario. El arreglo lo crea
        // arrayUnion la primera vez que sigue a alguien.
        val ref = db.collection("users").document(uid)
        if (!ref.get().await().exists()) {
            ref.set(mapOf("creadoEn" to com.google.firebase.firestore.FieldValue.serverTimestamp(), "avisos" to true)).await()
        }
    }

    // -------------------------------------------------------------------------
    // Google mediante Credential Manager
    // -------------------------------------------------------------------------
    // GoogleSignInClient está obsoleto. Mucho tutorial sigue mostrándolo; esta
    // es la API vigente y la única que Google mantiene.

    private suspend fun credencialDeGoogle(contexto: Context): AuthCredential {
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
        val respuesta = gestor.getCredential(contexto, peticion)
        val credencial = respuesta.credential

        if (credencial !is CustomCredential ||
            credencial.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Google devolvió un tipo de credencial inesperado.")
        }

        val token = GoogleIdTokenCredential.createFrom(credencial.data).idToken
        return GoogleAuthProvider.getCredential(token, null)
    }

    /**
     * Convierte la cuenta anónima en permanente, conservando los favoritos.
     *
     * Los tres caminos posibles están contemplados porque los tres ocurren en
     * producción, y el segundo es sorprendentemente común: gente que reinstala
     * la app en un teléfono nuevo.
     */
    suspend fun vincularConGoogle(contexto: Context): Resultado {
        val credencial = try {
            credencialDeGoogle(contexto)
        } catch (e: GetCredentialCancellationException) {
            return Resultado.Cancelada
        } catch (e: NoCredentialException) {
            return Resultado.Fallo("No hay ninguna cuenta de Google configurada en este teléfono.")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager falló", e)
            return Resultado.Fallo("No se pudo abrir el selector de cuentas de Google.")
        }

        return vincular(credencial)
    }

    private suspend fun vincular(credencial: AuthCredential): Resultado {
        val actual = auth.currentUser ?: return Resultado.Fallo("No hay sesión activa.")
        val favoritosLocales = leerFavoritos(actual.uid)

        return try {
            // Camino feliz: el token entra en el UID que ya existe y los
            // favoritos guardados en Firestore ni se tocan.
            actual.linkWithCredential(credencial).await()
            Resultado.Vinculada

        } catch (e: FirebaseAuthUserCollisionException) {
            when (e.errorCode) {

                // Esa cuenta de Google ya tiene un UID propio, de otro teléfono.
                // Entramos en la cuenta verdadera y arrastramos los favoritos
                // que se hubieran guardado en la sesión anónima.
                "ERROR_CREDENTIAL_ALREADY_IN_USE" -> {
                    val credencialReal = e.updatedCredential ?: credencial
                    val resultado = auth.signInWithCredential(credencialReal).await()
                    val nuevoUid = resultado.user?.uid
                    if (nuevoUid != null) {
                        fusionarFavoritos(nuevoUid, favoritosLocales)
                        limpiarAnonimo(actual.uid, nuevoUid)
                    }
                    Resultado.Recuperada(favoritosLocales.size)
                }

                // El correo existe, pero registrado con otro proveedor.
                // Devolvemos la credencial huérfana para engancharla después de
                // que el usuario entre con el proveedor original.
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                    Resultado.Conflicto(e.email, credencial)

                else -> Resultado.Fallo(e.localizedMessage ?: "No se pudo guardar la cuenta.")
            }

        } catch (e: Exception) {
            if (e.message?.contains("already linked") == true) return Resultado.Vinculada
            Log.w(TAG, "Vinculación fallida", e)
            Resultado.Fallo(e.localizedMessage ?: "No se pudo guardar la cuenta.")
        }
    }

    /**
     * Segundo paso del conflicto: el usuario entró con su proveedor original y
     * ahora enganchamos la credencial que quedó suelta.
     *
     * Nota: fetchSignInMethodsForEmail dejó de ser fiable desde que Firebase
     * activó la protección contra enumeración de correos. Por eso preguntamos
     * al usuario en vez de deducirlo, que además es más honesto.
     */
    suspend fun resolverConflicto(contexto: Context, credencialPendiente: AuthCredential): Resultado {
        return try {
            val original = credencialDeGoogle(contexto)
            val resultado = auth.signInWithCredential(original).await()
            resultado.user?.linkWithCredential(credencialPendiente)?.await()
            Resultado.Vinculada
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo unir las cuentas", e)
            Resultado.Fallo(e.localizedMessage ?: "No se pudieron unir las cuentas.")
        }
    }

    // -------------------------------------------------------------------------
    // Favoritos entre cuentas
    // -------------------------------------------------------------------------

    private suspend fun leerFavoritos(uid: String): List<String> = runCatching {
        val doc = db.collection("users").document(uid).get().await()
        @Suppress("UNCHECKED_CAST")
        (doc.get("favoritos") as? List<String>).orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun fusionarFavoritos(uid: String, entrantes: List<String>) {
        if (entrantes.isEmpty()) return
        runCatching {
            db.collection("users").document(uid).set(
                mapOf("favoritos" to com.google.firebase.firestore.FieldValue.arrayUnion(*entrantes.toTypedArray())),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        }
    }

    private suspend fun limpiarAnonimo(uidAnonimo: String, uidNuevo: String) {
        if (uidAnonimo == uidNuevo) return
        runCatching { db.collection("users").document(uidAnonimo).delete().await() }
    }

    // -------------------------------------------------------------------------
    // Cierre y borrado
    // -------------------------------------------------------------------------

    suspend fun cerrarSesion(contexto: Context) {
        runCatching {
            CredentialManager.create(contexto)
                .clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
        }
        auth.signOut()
        iniciarSesionInvisible() // la app nunca se queda sin sesión
    }

    /**
     * Borrado definitivo desde dentro de la app.
     * El backend revoca el vínculo federado y elimina todo rastro.
     */
    suspend fun borrarCuenta(contexto: Context) {
        funciones.getHttpsCallable("borrarCuenta").call(emptyMap<String, Any>()).await()
        runCatching { auth.signOut() }
        cerrarSesion(contexto)
    }

    companion object {
        private const val TAG = "AuthRepo"
    }
}
