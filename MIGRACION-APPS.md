# Migrar las apps al servidor Spring Boot

Las apps de Android y iOS llaman hoy a `httpsCallable(...)`, que es específico de Cloud Functions. Con Spring Boot pasan a ser llamadas REST normales con el ID token de Firebase en la cabecera.

Son **cinco puntos de llamada en total**. Todo lo demás —Firestore, Auth, FCM, enlaces profundos, la interfaz entera— se queda exactamente igual, porque esas partes hablan con Firebase directamente y Firebase no se ha movido.

---

## Qué NO cambia

- La lectura del directorio y las publicaciones desde Firestore.
- El inicio de sesión anónimo, con Google y con Apple.
- Las suscripciones a topics de FCM.
- Las Firestore Security Rules.
- Todo el código de interfaz.

---

## Android

### 1. Añade el cliente HTTP

`app/build.gradle.kts`, dentro de `dependencies`:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

Y quita `implementation(libs.firebase.functions)`, que ya no se usa.

En `defaultConfig`, junto a `WEB_CLIENT_ID`:

```kotlin
buildConfigField("String", "API_BASE", "\"https://TU-SERVIDOR\"")
```

### 2. Crea `data/ApiRelay.kt`

```kotlin
package com.tuempresa.creatorhub.data

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
import java.util.concurrent.TimeUnit

/**
 * Cliente del servidor Relé.
 *
 * Cada petición lleva el ID token de Firebase. El token caduca cada hora, así
 * que lo pedimos en cada llamada en lugar de guardarlo: el SDK lo refresca
 * solo y devuelve el vigente desde caché, así que no cuesta nada.
 */
object ApiRelay {

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private suspend fun token(): String =
        FirebaseAuth.getInstance().currentUser
            ?.getIdToken(false)?.await()?.token
            ?: error("No hay sesión activa.")

    private suspend fun ejecutar(peticion: Request.Builder): String =
        withContext(Dispatchers.IO) {
            val respuesta = cliente.newCall(
                peticion.header("Authorization", "Bearer ${token()}").build()
            ).execute()

            respuesta.use {
                val cuerpo = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    // El servidor manda el motivo en el campo "message" de
                    // Spring. Mostrarlo tal cual es más útil que un "error 400".
                    val motivo = runCatching {
                        JSONObject(cuerpo).optString("message")
                    }.getOrNull()
                    error(motivo?.takeIf { m -> m.isNotBlank() } ?: "Error ${it.code}")
                }
                cuerpo
            }
        }

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

    suspend fun borrarCuenta() {
        ejecutar(
            Request.Builder()
                .url("${BuildConfig.API_BASE}/api/cuenta")
                .delete()
        )
    }
}
```

### 3. Cambia los dos puntos de llamada

**`data/DirectorioRepo.kt`** — sustituye el cuerpo de `reportarEnlaceRoto`:

```kotlin
suspend fun reportarEnlaceRoto(videoId: String?, creatorId: String?, motivo: String = "enlace_roto") {
    ApiRelay.reportarEnlace(videoId, creatorId, motivo)
}
```

Quita de esa clase el parámetro `funciones: FirebaseFunctions` del constructor y su import.

**`data/AuthRepo.kt`** — en `borrarCuenta`:

```kotlin
suspend fun borrarCuenta(contexto: Context) {
    ApiRelay.borrarCuenta()
    runCatching { auth.signOut() }
    cerrarSesion(contexto)
}
```

Quita también ahí el parámetro `funciones` y el import de `FirebaseFunctions`.

---

## iOS

### 1. Crea `Datos/ApiRelay.swift`

```swift
import Foundation
import FirebaseAuth

/// Cliente del servidor Relé.
///
/// Cada petición lleva el ID token de Firebase. Caduca cada hora, así que lo
/// pedimos en cada llamada en lugar de guardarlo: el SDK lo refresca solo.
enum ApiRelay {

    static let base = "https://TU-SERVIDOR"

    struct ErrorApi: LocalizedError {
        let mensaje: String
        var errorDescription: String? { mensaje }
    }

    private static func token() async throws -> String {
        guard let usuario = Auth.auth().currentUser else {
            throw ErrorApi(mensaje: "No hay sesión activa.")
        }
        return try await usuario.getIDToken()
    }

    @discardableResult
    private static func ejecutar(
        _ ruta: String,
        metodo: String,
        cuerpo: [String: Any]? = nil
    ) async throws -> Data {

        guard let url = URL(string: base + ruta) else {
            throw ErrorApi(mensaje: "URL no válida.")
        }

        var peticion = URLRequest(url: url)
        peticion.httpMethod = metodo
        peticion.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")

        if let cuerpo {
            peticion.setValue("application/json", forHTTPHeaderField: "Content-Type")
            peticion.httpBody = try JSONSerialization.data(withJSONObject: cuerpo)
        }

        let (datos, respuesta) = try await URLSession.shared.data(for: peticion)

        guard let http = respuesta as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            // El servidor manda el motivo en "message". Mostrarlo tal cual es
            // más útil que un "error 400".
            let motivo = (try? JSONSerialization.jsonObject(with: datos) as? [String: Any])?["message"] as? String
            throw ErrorApi(mensaje: motivo ?? "No se pudo completar la operación.")
        }

        return datos
    }

    static func reportarEnlace(videoId: String?, creatorId: String?, motivo: String) async throws {
        try await ejecutar("/api/reportes", metodo: "POST", cuerpo: [
            "videoId": videoId as Any,
            "creatorId": creatorId as Any,
            "reason": motivo
        ])
    }

    static func borrarCuenta() async throws {
        try await ejecutar("/api/cuenta", metodo: "DELETE")
    }

    static func guardarTokenApple(codigo: String) async throws {
        try await ejecutar("/api/apple/token", metodo: "POST", cuerpo: [
            "authorizationCode": codigo
        ])
    }
}
```

### 2. Cambia los tres puntos de llamada

**`Datos/DirectorioStore.swift`** — `reportarEnlaceRoto`:

```swift
func reportarEnlaceRoto(videoId: String?, creatorId: String?) async {
    do {
        try await ApiRelay.reportarEnlace(videoId: videoId, creatorId: creatorId, motivo: "enlace_roto")
        mensaje = "Gracias. Vamos a revisar ese enlace."
    } catch {
        mensaje = "No se pudo enviar el reporte. Inténtalo más tarde."
    }
}
```

**`Datos/AutenticacionStore.swift`** — dentro de `vincularConApple`, donde se manda el `authorizationCode`:

```swift
if let codigoData = credencialApple.authorizationCode,
   let codigo = String(data: codigoData, encoding: .utf8) {
    Task { try? await ApiRelay.guardarTokenApple(codigo: codigo) }
}
```

Y en `borrarCuenta`:

```swift
func borrarCuenta() async {
    ocupado = true
    defer { ocupado = false }
    do {
        try await ApiRelay.borrarCuenta()
        try? Auth.auth().signOut()
        await iniciarSesionInvisible()
        mensaje = "Cuenta borrada. Puedes seguir usando la app como invitado."
    } catch {
        mensaje = "No se pudo borrar la cuenta. Inténtalo más tarde."
    }
}
```

### 3. Quita la dependencia

En `project.yml`, elimina la línea `product: FirebaseFunctions` y su bloque `- package: Firebase` correspondiente. Después, `xcodegen generate`.

Las dos clases pierden también `private let funciones = Functions.functions()` y el `import FirebaseFunctions`.

---

## Panel de curaduría

Ya está migrado en `admin/app.js`. Solo tienes que poner la URL de tu servidor en `admin/firebase-config.js`:

```javascript
export const API_BASE = 'https://TU-SERVIDOR';
```

Y añadir ese origen a `CORS_ORIGENES` en el servidor, o el navegador bloqueará las peticiones.

---

## Cómo comprobar que funcionó

1. Abre el panel, agrega un creador. Si el testigo pasa a verde, el handshake con el hub funciona y el token de admin se está validando bien.
2. Desde la app, toca "El enlace no funciona" en cualquier publicación. Debe aparecer en la pestaña Reportes del panel.
3. Desde Ajustes, borra la cuenta de prueba. Si vuelve a entrar como invitado, el flujo completo está bien.

Si el primer paso falla con un 403, el claim `admin` no está en tu token: cierra sesión y vuelve a entrar en el panel después de haberte nombrado administrador.
