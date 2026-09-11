import Foundation
import FirebaseFirestore

// Los nombres de las propiedades tienen que coincidir con los campos que
// escribe el backend. Con Codable, un nombre mal escrito no da error: llega
// nil y se ve como un hueco en la interfaz.

struct Conexion: Codable, Hashable {
    var url: String = ""
    var handle: String?
    var channelId: String?
}

struct Creador: Codable, Identifiable, Hashable {
    @DocumentID var id: String?
    var name: String = ""
    var category: String = "otros"
    var bio: String?
    var photoUrl: String?
    var platforms: [String: Conexion] = [:]
    var active: Bool = true

    static let ordenPlataformas = [
        "youtube", "tiktok", "twitch", "instagram", "spotify", "patreon", "web"
    ]

    /// Plataformas en el orden en que se muestran, sin las vacías.
    var conexionesOrdenadas: [(plataforma: String, conexion: Conexion)] {
        Creador.ordenPlataformas.compactMap { p in
            platforms[p].map { (p, $0) }
        }
    }
}

struct Publicacion: Codable, Identifiable, Hashable {
    @DocumentID var id: String?
    var videoId: String = ""
    var creatorId: String = ""
    var creatorName: String?
    var platform: String = "youtube"
    var title: String = ""
    var descripcionTexto: String?
    var thumbnailUrl: String?
    var url: String?
    var publishedAt: Timestamp?
    var status: String = "ok"
    var overrideUrl: String?
    var overridePlatform: String?
    var esEnVivo: Bool = false
    var tipo: String = "video"

    enum CodingKeys: String, CodingKey {
        case id, videoId, creatorId, creatorName, platform, title
        case descripcionTexto = "description" // `description` choca con CustomStringConvertible
        case thumbnailUrl, url, publishedAt, status, overrideUrl, overridePlatform
        case esEnVivo, tipo
    }

    var fueMovido: Bool {
        status == "moved" && !(overrideUrl ?? "").isEmpty
    }

    /// A dónde lleva realmente el botón. Si el equipo redirigió el contenido
    /// porque lo tumbaron de YouTube, el destino es el nuevo, no el original.
    var destino: Destino {
        fueMovido
            ? Destino(plataforma: overridePlatform ?? "web", url: overrideUrl, videoId: nil)
            : Destino(plataforma: platform, url: url, videoId: videoId)
    }
}

struct Destino {
    let plataforma: String
    let url: String?
    let videoId: String?
}

struct Perfil: Codable {
    var favoritos: [String] = []
    var escalaTexto: String = "normal"
    var tema: String = "sistema"
    var avisos: Bool = true
}

/// Escala propia de la app, encima de la Dynamic Type del sistema.
enum EscalaTexto: String, CaseIterable, Identifiable {
    case normal, grande, muyGrande

    var id: String { rawValue }

    var etiqueta: String {
        switch self {
        case .normal: return "Normal"
        case .grande: return "Grande"
        case .muyGrande: return "Muy grande"
        }
    }

    var factor: CGFloat {
        switch self {
        case .normal: return 1.0
        case .grande: return 1.2
        case .muyGrande: return 1.45
        }
    }

    static func desde(_ clave: String?) -> EscalaTexto {
        EscalaTexto(rawValue: clave ?? "normal") ?? .normal
    }
}
