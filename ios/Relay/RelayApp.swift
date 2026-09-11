import SwiftUI
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseMessaging
import GoogleSignIn
import UserNotifications

@main
struct RelayApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegado

    @StateObject private var directorio = DirectorioStore()
    @StateObject private var autenticacion = AutenticacionStore()

    var body: some Scene {
        WindowGroup {
            RaizVista()
                .environmentObject(directorio)
                .environmentObject(autenticacion)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}

/// SwiftUI todavía no cubre el registro de notificaciones remotas, así que
/// hace falta un AppDelegate para puentear APNs con FCM.
final class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions opciones: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()

        UNUserNotificationCenter.current().delegate = self
        Messaging.messaging().delegate = self

        Task { await pedirPermisoDeAvisos() }
        application.registerForRemoteNotifications()

        return true
    }

    /// El permiso se pide al arrancar porque sin él el producto no hace nada
    /// útil. Si el usuario dice que no, la app sigue funcionando como
    /// directorio; simplemente no avisa.
    private func pedirPermisoDeAvisos() async {
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .badge, .sound])
    }

    /// Sin este puente, FCM no obtiene el token de APNs y las notificaciones
    /// nunca llegan aunque todo lo demás esté bien configurado. Es el fallo
    /// silencioso más común al integrar FCM en iOS.
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        print("No se pudo registrar en APNs: \(error.localizedDescription)")
    }
}

// MARK: - Recepción de avisos

extension AppDelegate: UNUserNotificationCenterDelegate {

    /// App en primer plano. No mostramos nada: la lista de novedades ya se
    /// actualiza sola por el listener de Firestore, y sacar una notificación
    /// encima de la pantalla que el usuario está mirando sería ruido.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        []
    }

    /// El usuario tocó la notificación. Un toque, y el video ya está
    /// abriéndose en su app oficial: cero pantallas intermedias.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let datos = response.notification.request.content.userInfo

        guard let plataforma = datos["platform"] as? String else { return }
        let videoId = datos["videoId"] as? String
        let url = datos["url"] as? String
        let tipo = datos["tipo"] as? String

        await MainActor.run {
            Enrutador.abrirVideo(
                plataforma: plataforma,
                videoId: videoId,
                url: url,
                campana: tipo == "movido" ? "contenido_movido" : "aviso_publicacion"
            )
        }
    }
}

extension AppDelegate: MessagingDelegate {

    /// El token se regenera al restaurar el teléfono desde una copia de
    /// seguridad o tras mucho tiempo sin uso. Cuando eso pasa, las
    /// suscripciones a topics se pierden y el usuario deja de recibir avisos
    /// sin motivo aparente.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken token: String?) {
        guard let uid = Auth.auth().currentUser?.uid else { return }

        Task {
            let db = Firestore.firestore()
            guard let doc = try? await db.collection("users").document(uid).getDocument(),
                  let favoritos = doc.get("favoritos") as? [String]
            else { return }

            for id in favoritos {
                try? await Messaging.messaging().subscribe(toTopic: "creator_\(id)")
            }
            print("Suscripciones rehechas: \(favoritos.count)")
        }
    }
}
