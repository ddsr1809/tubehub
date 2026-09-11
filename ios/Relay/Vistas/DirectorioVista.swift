import SwiftUI

// El directorio es cerrado: solo aparecen los creadores que el equipo aprobó.
// Por eso no hay buscador abierto hacia todo YouTube, y por eso la app no cae
// en la categoría de "directorio genérico sin curaduría" que la Guideline
// 3.2.2 de Apple rechaza.

private let TEMAS: [(clave: String, nombre: String)] = [
    ("todos", "Todos"), ("comida", "Comida"), ("cine", "Cine"),
    ("politica", "Política"), ("musica", "Música"), ("noticias", "Noticias"),
    ("salud", "Salud"), ("tecnologia", "Tecnología"), ("otros", "Otros")
]

struct DirectorioVista: View {
    @EnvironmentObject private var directorio: DirectorioStore
    @Environment(\.paleta) private var paleta
    @State private var tema = "todos"
    @State private var seleccionado: String?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                TextoRelay("Directorio", estilo: .titulo)
                    .padding(.horizontal, Espacio.md)
                    .padding(.top, Espacio.sm)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Espacio.sm) {
                        ForEach(temasConGente, id: \.clave) { item in
                            botonDeTema(item)
                        }
                    }
                    .padding(.horizontal, Espacio.md)
                    .padding(.vertical, Espacio.md)
                }

                if visibles.isEmpty {
                    Vacio(
                        titulo: "Nada en este tema todavía",
                        mensaje: "Estamos sumando creadores poco a poco. Prueba con otro tema."
                    )
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(visibles) { creador in
                                FilaCreador(
                                    creador: creador,
                                    siguiendo: directorio.sigue(creador.id ?? ""),
                                    onAbrir: { seleccionado = creador.id },
                                    onSeguir: {
                                        Task { await directorio.alternarFavorito(creador.id ?? "") }
                                    }
                                )
                            }
                        }
                        .padding(.horizontal, Espacio.md)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(paleta.fondo)
            .navigationDestination(item: $seleccionado) { id in
                CreadorVista(creadorId: id)
            }
        }
    }

    private var visibles: [Creador] {
        tema == "todos" ? directorio.creadores : directorio.creadores.filter { $0.category == tema }
    }

    /// Solo mostramos los temas que de verdad tienen a alguien dentro.
    /// Una pestaña vacía es una promesa incumplida.
    private var temasConGente: [(clave: String, nombre: String)] {
        let usados = Set(directorio.creadores.map(\.category))
        return TEMAS.filter { $0.clave == "todos" || usados.contains($0.clave) }
    }

    private func botonDeTema(_ item: (clave: String, nombre: String)) -> some View {
        let activo = item.clave == tema
        return Button {
            tema = item.clave
        } label: {
            TextoRelay(
                item.nombre,
                estilo: .secundario,
                color: activo ? paleta.acentoTexto : paleta.texto
            )
            .padding(.horizontal, Espacio.md)
            .frame(minHeight: Tactil.minimo)
            .background(activo ? paleta.acento : .clear)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(activo ? paleta.acento : paleta.borde, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(activo ? [.isButton, .isSelected] : .isButton)
    }
}

/// Perfil del creador.
///
/// Aquí se materializa la idea del "Creador" como entidad, no del canal. Una
/// persona publica en varios lugares; la app los junta bajo un solo perfil y
/// cada botón dice en palabras qué va a pasar al tocarlo.
struct CreadorVista: View {
    let creadorId: String

    @EnvironmentObject private var directorio: DirectorioStore
    @Environment(\.paleta) private var paleta
    @Environment(\.dismiss) private var cerrar

    private var creador: Creador? {
        directorio.creadores.first { $0.id == creadorId }
    }

    var body: some View {
        Group {
            if let creador {
                contenido(creador)
            } else {
                Vacio(
                    titulo: "Este creador ya no está",
                    mensaje: "Puede que lo hayamos retirado del directorio. Vuelve al listado para ver a los demás."
                ) {
                    BotonGrande(titulo: "Volver al directorio") { cerrar() }
                }
            }
        }
        .background(paleta.fondo)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func contenido(_ creador: Creador) -> some View {
        let siguiendo = directorio.sigue(creadorId)

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(spacing: Espacio.sm) {
                    Avatar(url: creador.photoUrl, nombre: creador.name, tamano: 88)
                    TextoRelay(creador.name, estilo: .titulo)
                    if let bio = creador.bio, !bio.isEmpty {
                        TextoRelay(bio, estilo: .cuerpo)
                            .multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Espacio.lg)

                BotonGrande(
                    titulo: siguiendo ? "Ya recibes sus avisos" : "Avísame cuando publique",
                    subtitulo: siguiendo
                        ? "Toca para dejar de recibirlos"
                        : "Te llegará una notificación a este teléfono",
                    variante: siguiendo ? .secundario : .primario
                ) {
                    Task { await directorio.alternarFavorito(creadorId) }
                }

                TextoRelay("Dónde publica", estilo: .seccion)
                    .padding(.top, Espacio.lg)
                    .padding(.bottom, Espacio.md)

                if creador.conexionesOrdenadas.isEmpty {
                    TextoRelay("Todavía no hemos agregado sus enlaces.", estilo: .cuerpo)
                }

                ForEach(creador.conexionesOrdenadas, id: \.plataforma) { item in
                    BotonGrande(
                        titulo: Enrutador.accionDe(item.plataforma),
                        subtitulo: "Se abre la app de \(Enrutador.nombreDe(item.plataforma))",
                        variante: .secundario
                    ) {
                        Enrutador.abrirCanal(
                            plataforma: item.plataforma,
                            url: item.conexion.url,
                            campana: "perfil_creador"
                        )
                    }
                }

                TextoRelay(
                    "Los videos se ven en la app oficial de cada plataforma. Esta app solo te avisa y te lleva hasta allá.",
                    estilo: .secundario
                )
                .padding(.top, Espacio.lg)
                .padding(.bottom, Espacio.xxl)
            }
            .padding(Espacio.md)
        }
    }
}
