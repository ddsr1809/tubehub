import SwiftUI
import FirebaseFirestore

/// Navegación anclada abajo.
///
/// Los teléfonos actuales son demasiado altos para alcanzar la parte superior
/// con el pulgar sin recolocar la mano, y recolocar la mano es justo lo que
/// cuesta a quien tiene menos destreza. Todo lo que se toca vive en el tercio
/// inferior de la pantalla.
struct RaizVista: View {
    @EnvironmentObject private var directorio: DirectorioStore
    @EnvironmentObject private var autenticacion: AutenticacionStore

    @State private var listo = false
    @State private var pestana = 0

    var body: some View {
        Group {
            if listo {
                TabView(selection: $pestana) {
                    NovedadesVista(onIrAlDirectorio: { pestana = 1 })
                        .tabItem { Text("Novedades") }
                        .tag(0)

                    DirectorioVista()
                        .tabItem { Text("Directorio") }
                        .tag(1)

                    AjustesVista()
                        .tabItem { Text("Ajustes") }
                        .tag(2)
                }
            } else {
                PantallaDeCarga()
            }
        }
        .temaRelay(
            preferencia: directorio.perfil.tema,
            escala: EscalaTexto.desde(directorio.perfil.escalaTexto)
        )
        .task {
            // Sesión anónima antes que nada: sin UID no hay lecturas posibles.
            await autenticacion.iniciarSesionInvisible()
            directorio.empezarAEscuchar()
            listo = true
        }
        .alert(
            "Aviso",
            isPresented: Binding(
                get: { directorio.mensaje != nil || autenticacion.mensaje != nil },
                set: { if !$0 { directorio.mensaje = nil; autenticacion.mensaje = nil } }
            )
        ) {
            Button("Entendido", role: .cancel) {
                directorio.mensaje = nil
                autenticacion.mensaje = nil
            }
        } message: {
            Text(directorio.mensaje ?? autenticacion.mensaje ?? "")
        }
    }
}

private struct PantallaDeCarga: View {
    @Environment(\.paleta) private var paleta

    var body: some View {
        VStack(spacing: Espacio.md) {
            ProgressView().tint(paleta.acento)
            TextoRelay("Un momento…", estilo: .cuerpo, color: paleta.textoSuave)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(paleta.fondo)
    }
}

// MARK: - Novedades

// Nada de scroll infinito ni recomendaciones. Aquí solo aparece lo que
// publicaron las personas que el usuario eligió seguir, en orden de tiempo.
// Esa previsibilidad es la propuesta de valor entera.

struct NovedadesVista: View {
    let onIrAlDirectorio: () -> Void

    @EnvironmentObject private var directorio: DirectorioStore
    @Environment(\.paleta) private var paleta

    var body: some View {
        Group {
            if directorio.perfil.favoritos.isEmpty {
                Vacio(
                    titulo: "Todavía no sigues a nadie",
                    mensaje: "Elige a los creadores que te interesan y te avisaremos aquí cada vez que publiquen algo nuevo."
                ) {
                    BotonGrande(titulo: "Ver el directorio", accion: onIrAlDirectorio)
                }
            } else if directorio.publicaciones.isEmpty {
                Vacio(
                    titulo: "Sin novedades por ahora",
                    mensaje: sinNovedades
                )
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Espacio.lg) {
                        TextoRelay("Novedades", estilo: .titulo)
                            .padding(.bottom, Espacio.sm)

                        ForEach(directorio.publicaciones) { publicacion in
                            TarjetaPublicacion(publicacion: publicacion)
                        }
                    }
                    .padding(Espacio.md)
                }
            }
        }
        .background(paleta.fondo)
    }

    private var sinNovedades: String {
        let n = directorio.perfil.favoritos.count
        return "Sigues a \(n) \(n == 1 ? "creador" : "creadores"). En cuanto alguno publique, el aviso llega a este teléfono."
    }
}

private struct TarjetaPublicacion: View {
    let publicacion: Publicacion

    @EnvironmentObject private var directorio: DirectorioStore
    @Environment(\.paleta) private var paleta

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: abrir) {
                VStack(alignment: .leading, spacing: 0) {
                    if let url = publicacion.thumbnailUrl, let destino = URL(string: url) {
                        AsyncImage(url: destino) { fase in
                            if case .success(let imagen) = fase {
                                imagen.resizable().scaledToFill()
                            } else {
                                paleta.superficieAlta
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .clipped()
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        TextoRelay(encabezado, estilo: .secundario)
                        TextoRelay(publicacion.title, estilo: .cuerpoFuerte)
                            .lineLimit(3)
                            .multilineTextAlignment(.leading)

                        if publicacion.fueMovido {
                            TextoRelay(
                                "Este video cambió de lugar. El botón te lleva al sitio nuevo.",
                                estilo: .secundario,
                                color: paleta.acento
                            )
                            .padding(.top, Espacio.sm)
                        }
                    }
                    .padding(Espacio.md)
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Abrir el video \(publicacion.title) de \(publicacion.creatorName ?? "")")
            .accessibilityAddTraits(.isButton)

            VStack(spacing: 0) {
                BotonGrande(
                    titulo: publicacion.esEnVivo ? "Ver en vivo" : "Ver el video",
                    subtitulo: "Se abre en \(Enrutador.nombreDe(publicacion.destino.plataforma))",
                    accion: abrir
                )

                Button {
                    Task {
                        await directorio.reportarEnlaceRoto(
                            videoId: publicacion.id,
                            creatorId: publicacion.creatorId
                        )
                    }
                } label: {
                    TextoRelay("El enlace no funciona", estilo: .secundario)
                        .underline()
                        .frame(maxWidth: .infinity, minHeight: Tactil.minimo)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, Espacio.md)
            .padding(.bottom, Espacio.sm)
        }
        .background(paleta.superficie)
        .overlay(
            RoundedRectangle(cornerRadius: 20).strokeBorder(paleta.borde, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private func abrir() {
        let destino = publicacion.destino
        Enrutador.abrirVideo(
            plataforma: destino.plataforma,
            videoId: destino.videoId,
            url: destino.url,
            campana: "novedades"
        )
    }

    private var encabezado: String {
        "\(publicacion.creatorName ?? "") · \(Self.tiempoRelativo(publicacion.publishedAt))"
    }

    /// Fechas en palabras. "hace 2 horas" se entiende de un vistazo; una marca
    /// como 09/09/2026 15:04 obliga a hacer la resta mentalmente.
    static func tiempoRelativo(_ marca: Timestamp?) -> String {
        guard let fecha = marca?.dateValue() else { return "" }
        let minutos = Int(Date().timeIntervalSince(fecha) / 60)

        switch minutos {
        case ..<2: return "hace un momento"
        case ..<60: return "hace \(minutos) minutos"
        case ..<120: return "hace una hora"
        case ..<1440: return "hace \(minutos / 60) horas"
        case ..<2880: return "ayer"
        case ..<10080: return "hace \(minutos / 1440) días"
        default:
            let formato = DateFormatter()
            formato.locale = Locale(identifier: "es_MX")
            formato.dateFormat = "d 'de' MMMM"
            return formato.string(from: fecha)
        }
    }
}
