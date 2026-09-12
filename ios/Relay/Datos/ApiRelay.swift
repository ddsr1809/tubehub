import Foundation
import FirebaseAuth

/// Cliente del servidor Relé.
///
/// Sustituye a las Cloud Functions callable. La diferencia práctica es que
/// ahora nosotros somos responsables de adjuntar el token: el SDK de Functions
/// lo hacía por debajo.
///
/// Pedimos el token en cada llamada en lugar de guardarlo. Caduca cada hora y
/// el SDK lo refresca solo, devolviéndolo desde caché mientras siga vigente,
/// así que no cuesta nada y nunca mandamos uno caducado.
enum ApiRelay {

    /// URL del servidor, sin barra final: las rutas ya la llevan.
    /// En el simulador, localhost sí apunta a tu Mac, así que
    /// http://localhost:8080 funciona para desarrollo.
    #if DEBUG
    static let base = "http://localhost:8080"
    #else
    static let base = "https://TU-SERVIDOR"
    #endif

    /// Error con un mensaje ya listo para enseñar al usuario.
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
            throw ErrorApi(mensaje: "La dirección del servidor no es válida.")
        }

        var peticion = URLRequest(url: url)
        peticion.httpMethod = metodo
        peticion.timeoutInterval = 30
        peticion.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")

        if let cuerpo {
            peticion.setValue("application/json", forHTTPHeaderField: "Content-Type")
            peticion.httpBody = try JSONSerialization.data(withJSONObject: cuerpo)
        }

        let (datos, respuesta) = try await URLSession.shared.data(for: peticion)

        guard let http = respuesta as? HTTPURLResponse else {
            throw ErrorApi(mensaje: "Respuesta inesperada del servidor.")
        }

        guard (200..<300).contains(http.statusCode) else {
            // El servidor escribe el motivo en `message`, en lenguaje llano,
            // precisamente para que se pueda mostrar tal cual. Un "error 400"
            // pelado no le sirve a nadie.
            let json = try? JSONSerialization.jsonObject(with: datos) as? [String: Any]
            let motivo = json?["message"] as? String
            throw ErrorApi(mensaje: motivo ?? "No se pudo completar la operación.")
        }

        return datos
    }

    // MARK: - Rutas

    /// Reporta un enlace roto. Alimenta la redirección de emergencia.
    static func reportarEnlace(videoId: String?, creatorId: String?, motivo: String) async throws {
        try await ejecutar("/api/reportes", metodo: "POST", cuerpo: [
            "videoId": videoId as Any,
            "creatorId": creatorId as Any,
            "reason": motivo
        ])
    }

    /// Borrado definitivo. El servidor revoca el vínculo con Apple y elimina
    /// todo rastro, como exige la Guideline 5.1.1(v).
    static func borrarCuenta() async throws {
        try await ejecutar("/api/cuenta", metodo: "DELETE")
    }

    /// Manda el código de autorización de Apple para que el servidor lo canjee
    /// por un refresh token y lo guarde. Sin ese token guardado no se puede
    /// revocar el vínculo al borrar la cuenta, y Apple rechaza la app.
    static func guardarTokenApple(codigo: String) async throws {
        try await ejecutar("/api/apple/token", metodo: "POST", cuerpo: [
            "authorizationCode": codigo
        ])
    }
}
