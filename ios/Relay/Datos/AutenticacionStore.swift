import Foundation
import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import GoogleSignIn

/// Identidad.
///
/// Principio de diseño: nadie ve una pantalla de registro al abrir la app.
/// Una sola equivocación en el teclado durante el alta basta para que una
/// persona mayor abandone el producto. Entramos en modo anónimo y solo
/// ofrecemos "guardar mi cuenta" cuando ya hay favoritos que conservar.
///
/// De paso cubre el criterio 3.3.8 de WCAG (Accessible Authentication): no
/// obligamos a nadie a recordar una contraseña.
@MainActor
final class AutenticacionStore: NSObject, ObservableObject {

    @Published var esAnonimo = true
    @Published var correo: String?
    @Published var ocupado = false
    @Published var mensaje: String?
    @Published var conflicto: Conflicto?

    struct Conflicto: Identifiable {
        let id = UUID()
        let correo: String?
        let credencialPendiente: AuthCredential
    }

    private let db = Firestore.firestore()
    private let funciones = Functions.functions()

    /// Nonce en crudo del intento actual de Sign in with Apple.
    /// Apple recibe su hash SHA-256 y Firebase el valor sin cifrar; así se
    /// prueba que quien canjea el token es quien inició la sesión.
    private var nonceActual: String?
    private var continuacionApple: CheckedContinuation<ASAuthorizationAppleIDCredential, Error>?

    // MARK: - Arranque

    /// Se llama al abrir la app. Silencioso, sin interfaz.
    func iniciarSesionInvisible() async {
        if let usuario = Auth.auth().currentUser {
            actualizarEstado(usuario)
            return
        }
        do {
            let resultado = try await Auth.auth().signInAnonymously()
            let uid = resultado.user.uid

            // Ojo: aquí NO escribimos "favoritos" a lista vacía. Con merge, eso
            // no respeta el arreglo existente: lo reemplaza. Si la sesión se
            // restaura tarde, borraríamos los creadores del usuario.
            let ref = db.collection("users").document(uid)
            let doc = try await ref.getDocument()
            if !doc.exists {
                try await ref.setData([
                    "creadoEn": FieldValue.serverTimestamp(),
                    "avisos": true
                ])
            }
            actualizarEstado(resultado.user)
        } catch {
            mensaje = "No se pudo conectar. Revisa tu internet y vuelve a abrir la app."
        }
    }

    private func actualizarEstado(_ usuario: User?) {
        esAnonimo = usuario?.isAnonymous ?? true
        correo = usuario?.email
    }

    // MARK: - Sign in with Apple

    /// Obligatorio por la Guideline 4.8: si ofrecemos Google, Apple exige
    /// ofrecer también su inicio de sesión, con la misma visibilidad.
    func vincularConApple() async {
        ocupado = true
        defer { ocupado = false }

        do {
            let nonce = Self.nonceAleatorio()
            nonceActual = nonce

            let peticion = ASAuthorizationAppleIDProvider().createRequest()
            peticion.requestedScopes = [.fullName, .email]
            peticion.nonce = Self.sha256(nonce)

            let credencialApple = try await pedirAutorizacion(peticion)

            guard let tokenData = credencialApple.identityToken,
                  let token = String(data: tokenData, encoding: .utf8) else {
                mensaje = "Apple no devolvió un token válido."
                return
            }

            // El authorizationCode se manda al backend, que lo canjea por un
            // refresh token y lo guarda. Sin ese token guardado no se puede
            // revocar el vínculo al borrar la cuenta, y Apple rechaza la app
            // por incumplir la Guideline 5.1.1(v).
            if let codigoData = credencialApple.authorizationCode,
               let codigo = String(data: codigoData, encoding: .utf8) {
                Task {
                    _ = try? await funciones.httpsCallable("guardarTokenApple")
                        .call(["authorizationCode": codigo])
                }
            }

            let credencial = OAuthProvider.appleCredential(
                withIDToken: token,
                rawNonce: nonce,
                fullName: credencialApple.fullName
            )
            await vincular(credencial)

        } catch let error as ASAuthorizationError where error.code == .canceled {
            // El usuario cerró la hoja. No es un error que merezca aviso.
        } catch {
            mensaje = "No se pudo iniciar sesión con Apple."
        }
    }

    // MARK: - Google

    func vincularConGoogle(presentando controlador: UIViewController) async {
        ocupado = true
        defer { ocupado = false }

        guard let clientID = FirebaseApp.app()?.options.clientID else {
            mensaje = "Falta la configuración de Google en GoogleService-Info.plist."
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)

        do {
            let resultado = try await GIDSignIn.sharedInstance.signIn(withPresenting: controlador)
            guard let idToken = resultado.user.idToken?.tokenString else {
                mensaje = "Google no devolvió un token."
                return
            }
            let credencial = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: resultado.user.accessToken.tokenString
            )
            await vincular(credencial)
        } catch {
            let ns = error as NSError
            if ns.code == GIDSignInError.canceled.rawValue { return }
            mensaje = "No se pudo iniciar sesión con Google."
        }
    }

    // MARK: - Vinculación y colisiones

    /// Convierte la cuenta anónima en permanente conservando los favoritos.
    ///
    /// Los tres caminos posibles están contemplados porque los tres ocurren en
    /// producción, y el segundo es sorprendentemente común: gente que
    /// reinstala la app en un teléfono nuevo.
    private func vincular(_ credencial: AuthCredential) async {
        guard let actual = Auth.auth().currentUser else { return }
        let favoritosLocales = await leerFavoritos(actual.uid)

        do {
            // Camino feliz: el token entra en el UID que ya existe y los
            // favoritos guardados en Firestore ni se tocan.
            let resultado = try await actual.link(with: credencial)
            actualizarEstado(resultado.user)
            mensaje = "Tu cuenta quedó guardada. Si cambias de teléfono, tus creadores te siguen."

        } catch let error as NSError {
            switch AuthErrorCode(rawValue: error.code) {

            // Esa cuenta ya tiene un UID propio, de otro teléfono. Entramos en
            // la cuenta verdadera y arrastramos los favoritos de la anónima.
            case .credentialAlreadyInUse:
                let credencialReal = error.userInfo[AuthErrorUserInfoUpdatedCredentialKey]
                    as? AuthCredential ?? credencial
                do {
                    let resultado = try await Auth.auth().signIn(with: credencialReal)
                    await fusionarFavoritos(resultado.user.uid, favoritosLocales)
                    await limpiarAnonimo(actual.uid, resultado.user.uid)
                    actualizarEstado(resultado.user)
                    mensaje = favoritosLocales.isEmpty
                        ? "Ya tenías cuenta aquí y volvimos a entrar con ella."
                        : "Ya tenías cuenta aquí. Juntamos los \(favoritosLocales.count) creadores de este teléfono con los de antes."
                } catch {
                    mensaje = "No se pudo recuperar tu cuenta anterior."
                }

            // El correo existe, pero registrado con otro proveedor. Guardamos
            // la credencial huérfana para engancharla cuando el usuario entre
            // con el proveedor original.
            case .accountExistsWithDifferentCredential:
                conflicto = Conflicto(
                    correo: error.userInfo[AuthErrorUserInfoEmailKey] as? String,
                    credencialPendiente: error.userInfo[AuthErrorUserInfoUpdatedCredentialKey]
                        as? AuthCredential ?? credencial
                )

            case .providerAlreadyLinked:
                mensaje = "Esa cuenta ya estaba guardada."

            default:
                mensaje = "No se pudo guardar la cuenta. Inténtalo otra vez."
            }
        }
    }

    /// Segundo paso del conflicto: el usuario ya entró con su proveedor
    /// original y ahora enganchamos la credencial que quedó suelta.
    func resolverConflicto(usando proveedor: String, presentando controlador: UIViewController) async {
        guard let pendiente = conflicto?.credencialPendiente else { return }
        conflicto = nil

        if proveedor == "apple" {
            await vincularConApple()
        } else {
            await vincularConGoogle(presentando: controlador)
        }

        guard let usuario = Auth.auth().currentUser else { return }
        do {
            _ = try await usuario.link(with: pendiente)
            mensaje = "Las dos cuentas quedaron unidas."
        } catch {
            mensaje = "Entraste correctamente, pero no se pudieron unir las dos cuentas."
        }
    }

    // MARK: - Favoritos entre cuentas

    private func leerFavoritos(_ uid: String) async -> [String] {
        guard let doc = try? await db.collection("users").document(uid).getDocument() else {
            return []
        }
        return doc.get("favoritos") as? [String] ?? []
    }

    private func fusionarFavoritos(_ uid: String, _ entrantes: [String]) async {
        guard !entrantes.isEmpty else { return }
        try? await db.collection("users").document(uid)
            .setData(["favoritos": FieldValue.arrayUnion(entrantes)], merge: true)
    }

    private func limpiarAnonimo(_ uidAnonimo: String, _ uidNuevo: String) async {
        guard uidAnonimo != uidNuevo else { return }
        try? await db.collection("users").document(uidAnonimo).delete()
    }

    // MARK: - Cierre y borrado

    func cerrarSesion() async {
        GIDSignIn.sharedInstance.signOut()
        try? Auth.auth().signOut()
        await iniciarSesionInvisible() // la app nunca se queda sin sesión
        mensaje = "Sesión cerrada."
    }

    /// Borrado definitivo desde dentro de la app, como exige la Guideline
    /// 5.1.1(v). El backend revoca el vínculo con Apple y elimina todo rastro.
    func borrarCuenta() async {
        ocupado = true
        defer { ocupado = false }
        do {
            _ = try await funciones.httpsCallable("borrarCuenta").call([:])
            try? Auth.auth().signOut()
            await iniciarSesionInvisible()
            mensaje = "Cuenta borrada. Puedes seguir usando la app como invitado."
        } catch {
            mensaje = "No se pudo borrar la cuenta. Inténtalo más tarde."
        }
    }

    // MARK: - Nonce

    /// Cadena aleatoria de alta entropía. Apple recibe su hash y Firebase el
    /// valor crudo; si no coinciden, el token no se puede canjear. Es la misma
    /// idea que PKCE, aplicada al inicio de sesión nativo.
    static func nonceAleatorio(longitud: Int = 32) -> String {
        let caracteres = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var resultado = ""
        var restantes = longitud

        while restantes > 0 {
            var bytes = [UInt8](repeating: 0, count: 16)
            let estado = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            precondition(estado == errSecSuccess, "SecRandomCopyBytes falló")

            for byte in bytes where restantes > 0 && byte < 64 {
                resultado.append(caracteres[Int(byte)])
                restantes -= 1
            }
        }
        return resultado
    }

    static func sha256(_ texto: String) -> String {
        SHA256.hash(data: Data(texto.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

// MARK: - Puente entre el delegado de Apple y async/await

extension AutenticacionStore: ASAuthorizationControllerDelegate,
                              ASAuthorizationControllerPresentationContextProviding {

    private func pedirAutorizacion(
        _ peticion: ASAuthorizationAppleIDRequest
    ) async throws -> ASAuthorizationAppleIDCredential {
        try await withCheckedThrowingContinuation { continuacion in
            self.continuacionApple = continuacion
            let controlador = ASAuthorizationController(authorizationRequests: [peticion])
            controlador.delegate = self
            controlador.presentationContextProvider = self
            controlador.performRequests()
        }
    }

    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        Task { @MainActor in
            if let credencial = authorization.credential as? ASAuthorizationAppleIDCredential {
                continuacionApple?.resume(returning: credencial)
            } else {
                continuacionApple?.resume(throwing: ASAuthorizationError(.invalidResponse))
            }
            continuacionApple = nil
        }
    }

    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        Task { @MainActor in
            continuacionApple?.resume(throwing: error)
            continuacionApple = nil
        }
    }

    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }
}
