import Foundation
import UIKit

/// Motor de redirección.
///
/// Esta app nunca reproduce video. Su trabajo aquí es entregar al usuario
/// dentro de la app oficial del creador, sin `SFSafariViewController` ni
/// WebView. Eso es lo que mantiene la infraestructura en cero y lo que evita
/// problemas con los Términos de Servicio de las plataformas.
///
/// Estrategia, en orden:
///   1. Esquema propio de la app (youtube://, twitch://). Abre al instante si
///      está instalada. Requiere declarar el esquema en
///      LSApplicationQueriesSchemes, o `canOpenURL` devuelve siempre false.
///   2. Enlace https. Lo captura el Universal Link gracias al archivo AASA que
///      la plataforma publica en /.well-known/. Si la app no está, abre Safari.
///   3. Aviso claro al usuario. Nunca fallamos en silencio.
enum Enrutador {

    // MARK: - Etiquetas

    static func nombreDe(_ plataforma: String) -> String {
        switch plataforma {
        case "youtube": return "YouTube"
        case "tiktok": return "TikTok"
        case "twitch": return "Twitch"
        case "instagram": return "Instagram"
        case "spotify": return "Spotify"
        case "patreon": return "Patreon"
        case "web": return "su página"
        default: return "la app original"
        }
    }

    /// Etiqueta del botón, escrita para que se entienda sin saber de apps.
    static func accionDe(_ plataforma: String) -> String {
        switch plataforma {
        case "youtube": return "Ver videos largos"
        case "tiktok": return "Ver videos cortos"
        case "twitch": return "Ver transmisiones en vivo"
        case "instagram": return "Ver fotos y reels"
        case "spotify": return "Escuchar el pódcast"
        case "patreon": return "Apoyar al creador"
        default: return "Abrir su página"
        }
    }

    // MARK: - Apertura

    /// Abre un video concreto. Es lo que ocurre al tocar una notificación.
    @MainActor
    static func abrirVideo(
        plataforma: String,
        videoId: String?,
        url: String?,
        campana: String = "app"
    ) {
        var candidatas: [String] = []

        if plataforma == "youtube", let videoId, !videoId.isEmpty {
            candidatas.append("youtube://www.youtube.com/watch?v=\(videoId)")
            candidatas.append(conAtribucion("https://www.youtube.com/watch?v=\(videoId)", campana))
        }
        if let url, !url.isEmpty {
            candidatas.append(contentsOf: esquemasPropios(plataforma: plataforma, url: url))
            candidatas.append(conAtribucion(url, campana))
        }

        abrirPrimeraQueFuncione(candidatas, plataforma: plataforma)
    }

    /// Abre el perfil del creador en la plataforma elegida.
    @MainActor
    static func abrirCanal(plataforma: String, url: String?, campana: String = "perfil") {
        guard let url, !url.isEmpty else {
            avisar("Todavía no tenemos el enlace de este contenido.")
            return
        }
        var candidatas = esquemasPropios(plataforma: plataforma, url: url)
        candidatas.append(conAtribucion(url, campana))
        abrirPrimeraQueFuncione(candidatas, plataforma: plataforma)
    }

    @MainActor
    private static func abrirPrimeraQueFuncione(_ candidatas: [String], plataforma: String) {
        for texto in candidatas {
            guard let destino = URL(string: texto) else { continue }
            if UIApplication.shared.canOpenURL(destino) {
                UIApplication.shared.open(destino)
                return
            }
        }
        avisar(
            "No se pudo abrir \(nombreDe(plataforma)). Instálalo desde la App Store y vuelve a intentarlo."
        )
    }

    // MARK: - Esquemas

    /// Construye el esquema propietario a partir del usuario que aparece en la
    /// URL pública. Los esquemas cambian sin aviso, por eso el https siempre va
    /// después como red de seguridad.
    private static func esquemasPropios(plataforma: String, url: String) -> [String] {
        guard let usuario = extraerUsuario(de: url, plataforma: plataforma) else { return [] }

        switch plataforma {
        case "youtube":
            return ["youtube://www.youtube.com/channel/\(usuario)"]
        case "tiktok":
            return ["snssdk1128://user/profile/\(usuario)", "tiktok://user/@\(usuario)"]
        case "twitch":
            return ["twitch://stream/\(usuario)"]
        case "instagram":
            return ["instagram://user?username=\(usuario)"]
        case "spotify":
            return ["spotify:\(usuario.replacingOccurrences(of: "/", with: ":"))"]
        default:
            return []
        }
    }

    private static func extraerUsuario(de url: String, plataforma: String) -> String? {
        let patrones: [String: String] = [
            "youtube": #"youtube\.com/channel/([\w-]+)"#,
            "tiktok": #"tiktok\.com/@([\w.-]+)"#,
            "twitch": #"twitch\.tv/([\w-]+)"#,
            "instagram": #"instagram\.com/([\w.-]+)"#,
            "spotify": #"open\.spotify\.com/(.+)$"#
        ]
        guard let patron = patrones[plataforma],
              let regex = try? NSRegularExpression(pattern: patron, options: .caseInsensitive),
              let coincidencia = regex.firstMatch(
                  in: url, range: NSRange(url.startIndex..., in: url)
              ),
              let rango = Range(coincidencia.range(at: 1), in: url)
        else { return nil }

        return String(url[rango])
    }

    // MARK: - Atribución

    /// Conserva las etiquetas UTM al saltar entre apps.
    ///
    /// Se hace a mano en vez de con URLComponents porque este reordena y
    /// reescapa los parámetros existentes, y algunos enlaces de creador llevan
    /// tokens firmados que no toleran ser reescritos.
    static func conAtribucion(_ url: String, _ campana: String) -> String {
        guard url.hasPrefix("http://") || url.hasPrefix("https://") else { return url }

        var base = url
        var fragmento = ""
        if let corte = url.firstIndex(of: "#") {
            base = String(url[url.startIndex ..< corte])
            fragmento = String(url[corte...])
        }

        let etiquetas = [
            ("utm_source", "relay_app"),
            ("utm_medium", "push"),
            ("utm_campaign", campana)
        ]

        for (clave, valor) in etiquetas {
            // Si el enlace ya trae esa etiqueta, respetamos la del creador.
            if base.range(of: "[?&]\(clave)=", options: .regularExpression) != nil { continue }
            let codificado = valor.addingPercentEncoding(
                withAllowedCharacters: .alphanumerics
            ) ?? valor
            base += (base.contains("?") ? "&" : "?") + "\(clave)=\(codificado)"
        }

        return base + fragmento
    }

    @MainActor
    private static func avisar(_ texto: String) {
        guard let escena = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
            let raiz = escena.windows.first(where: \.isKeyWindow)?.rootViewController
        else { return }

        let alerta = UIAlertController(title: "No se pudo abrir", message: texto, preferredStyle: .alert)
        alerta.addAction(UIAlertAction(title: "Entendido", style: .default))
        raiz.present(alerta, animated: true)
    }
}
