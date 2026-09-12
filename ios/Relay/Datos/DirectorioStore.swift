import Foundation
import FirebaseAuth
import FirebaseFirestore
import FirebaseMessaging

/// Toda la lectura del directorio pasa por aquí.
///
/// El directorio es de solo lectura para la app: las Firestore Security Rules
/// bloquean cualquier escritura sobre /creators y /videos. Lo único que el
/// usuario modifica es su propio documento en /users.
@MainActor
final class DirectorioStore: ObservableObject {

    @Published var creadores: [Creador] = []
    @Published var publicaciones: [Publicacion] = []
    @Published var perfil = Perfil()
    @Published var mensaje: String?

    private let db = Firestore.firestore()

    private var registroCreadores: ListenerRegistration?
    private var registroPerfil: ListenerRegistration?
    private var registrosVideos: [ListenerRegistration] = []
    private var acumulado: [String: Publicacion] = [:]

    // MARK: - Escucha

    func empezarAEscuchar() {
        escucharCreadores()
        escucharPerfil()
    }

    func dejarDeEscuchar() {
        registroCreadores?.remove()
        registroPerfil?.remove()
        registrosVideos.forEach { $0.remove() }
        registrosVideos = []
    }

    private func escucharCreadores() {
        registroCreadores?.remove()
        registroCreadores = db.collection("creators")
            .whereField("active", isEqualTo: true)
            .order(by: "name")
            .addSnapshotListener { [weak self] snap, error in
                guard let self else { return }
                if let error {
                    print("Error leyendo el directorio: \(error.localizedDescription)")
                    return
                }
                self.creadores = snap?.documents.compactMap {
                    try? $0.data(as: Creador.self)
                } ?? []
            }
    }

    private func escucharPerfil() {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        registroPerfil?.remove()
        registroPerfil = db.collection("users").document(uid)
            .addSnapshotListener { [weak self] doc, error in
                guard let self else { return }
                if let error {
                    print("Error leyendo el perfil: \(error.localizedDescription)")
                    return
                }
                let nuevo = (try? doc?.data(as: Perfil.self)) ?? Perfil()
                let cambiaronFavoritos = nuevo.favoritos != self.perfil.favoritos
                self.perfil = nuevo

                if cambiaronFavoritos {
                    self.escucharPublicaciones(favoritos: nuevo.favoritos)
                    Task { await self.sincronizarTopics(nuevo.favoritos) }
                }
            }
    }

    /// Publicaciones de los creadores que sigue el usuario.
    ///
    /// Firestore admite como máximo 30 valores en un `in`, así que partimos en
    /// lotes y unimos los resultados en memoria. Con más de un centenar de
    /// favoritos convendría invertir el modelo y escribir un feed por usuario
    /// desde el backend, pero para este tamaño esto es más simple y más barato.
    private func escucharPublicaciones(favoritos: [String]) {
        registrosVideos.forEach { $0.remove() }
        registrosVideos = []
        acumulado = [:]

        guard !favoritos.isEmpty else {
            publicaciones = []
            return
        }

        for lote in favoritos.chunked(into: 30) {
            let registro = db.collection("videos")
                .whereField("creatorId", in: lote)
                .order(by: "publishedAt", descending: true)
                .limit(to: 30)
                .addSnapshotListener { [weak self] snap, error in
                    guard let self else { return }
                    if let error {
                        print("Error leyendo publicaciones: \(error.localizedDescription)")
                        return
                    }
                    for doc in snap?.documents ?? [] {
                        if let p = try? doc.data(as: Publicacion.self), let id = p.id {
                            self.acumulado[id] = p
                        }
                    }
                    self.publicaciones = self.acumulado.values
                        .filter { $0.status != "removed" }
                        .sorted {
                            ($0.publishedAt?.seconds ?? 0) > ($1.publishedAt?.seconds ?? 0)
                        }
                        .prefix(50)
                        .map { $0 }
                }
            registrosVideos.append(registro)
        }
    }

    // MARK: - Favoritos

    func sigue(_ creatorId: String) -> Bool {
        perfil.favoritos.contains(creatorId)
    }

    /// Seguir o dejar de seguir.
    ///
    /// Dos cosas a la vez: el favorito en Firestore, que sobrevive al cambio de
    /// teléfono, y el topic de FCM, que es lo que hace llegar el aviso a ESTE
    /// aparato. Con solo lo primero, el usuario vería el creador marcado pero
    /// no recibiría nada.
    func alternarFavorito(_ creatorId: String) async {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        let ref = db.collection("users").document(uid)
        let siguiendo = sigue(creatorId)

        do {
            if siguiendo {
                try await ref.setData(["favoritos": FieldValue.arrayRemove([creatorId])], merge: true)
                try? await Messaging.messaging().unsubscribe(fromTopic: Self.topic(creatorId))
            } else {
                try await ref.setData(["favoritos": FieldValue.arrayUnion([creatorId])], merge: true)
                try? await Messaging.messaging().subscribe(toTopic: Self.topic(creatorId))
            }
        } catch {
            mensaje = "No se pudo guardar el cambio. Revisa tu conexión."
        }
    }

    /// Los favoritos viven en la cuenta, pero los topics son por aparato: un
    /// teléfono nuevo no está suscrito a nada aunque la cuenta sí lo esté.
    func sincronizarTopics(_ favoritos: [String]) async {
        for id in favoritos {
            try? await Messaging.messaging().subscribe(toTopic: Self.topic(id))
        }
    }

    func guardarPreferencia(_ clave: String, _ valor: Any) async {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        try? await db.collection("users").document(uid).setData([clave: valor], merge: true)
    }

    /// Reporta un enlace roto. Alimenta la redirección de emergencia.
    func reportarEnlaceRoto(videoId: String?, creatorId: String?) async {
        do {
            try await ApiRelay.reportarEnlace(
                videoId: videoId,
                creatorId: creatorId,
                motivo: "enlace_roto"
            )
            mensaje = "Gracias. Vamos a revisar ese enlace."
        } catch {
            mensaje = "No se pudo enviar el reporte. Inténtalo más tarde."
        }
    }

    static func topic(_ creatorId: String) -> String { "creator_\(creatorId)" }
}

extension Array {
    func chunked(into tamano: Int) -> [[Element]] {
        stride(from: 0, to: count, by: tamano).map {
            Array(self[$0 ..< Swift.min($0 + tamano, count)])
        }
    }
}
