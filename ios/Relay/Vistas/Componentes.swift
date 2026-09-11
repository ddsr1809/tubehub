import SwiftUI

enum VarianteBoton { case primario, secundario, peligro }

/// Botón principal. 56pt de alto mínimo (no fijo: tiene que poder crecer si el
/// texto crece), etiqueta siempre escrita, nunca un icono suelto que haya que
/// interpretar.
struct BotonGrande: View {
    let titulo: String
    var subtitulo: String?
    var variante: VarianteBoton = .primario
    var habilitado: Bool = true
    let accion: () -> Void

    @Environment(\.paleta) private var paleta

    var body: some View {
        Button(action: accion) {
            VStack(alignment: .leading, spacing: 2) {
                TextoRelay(titulo, estilo: .boton, color: tinte)
                if let subtitulo {
                    TextoRelay(subtitulo, estilo: .secundario, color: tinte.opacity(0.75))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Espacio.lg)
            .padding(.vertical, Espacio.md)
            .frame(minHeight: Tactil.principal)
            .background(fondo)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(borde, lineWidth: variante == .primario ? 0 : 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(!habilitado)
        .opacity(habilitado ? 1 : 0.5)
        .padding(.bottom, Tactil.separacion)
        // Una sola etiqueta para VoiceOver, en vez de dos fragmentos sueltos
        // que se leerían como si no tuvieran relación entre sí.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(subtitulo.map { "\(titulo). \($0)" } ?? titulo)
        .accessibilityAddTraits(.isButton)
    }

    private var fondo: Color {
        switch variante {
        case .primario: return paleta.acento
        case .secundario: return paleta.superficie
        case .peligro: return .clear
        }
    }

    private var tinte: Color {
        switch variante {
        case .primario: return paleta.acentoTexto
        case .secundario: return paleta.texto
        case .peligro: return paleta.peligro
        }
    }

    private var borde: Color {
        switch variante {
        case .primario: return .clear
        case .secundario: return paleta.borde
        case .peligro: return paleta.peligro
        }
    }
}

/// Fila del directorio. El área táctil abarca la fila entera, no solo el texto.
struct FilaCreador: View {
    let creador: Creador
    let siguiendo: Bool
    let onAbrir: () -> Void
    let onSeguir: () -> Void

    @Environment(\.paleta) private var paleta

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Tactil.separacion) {
                Button(action: onAbrir) {
                    HStack(spacing: Espacio.md) {
                        Avatar(url: creador.photoUrl, nombre: creador.name)
                        VStack(alignment: .leading, spacing: 2) {
                            TextoRelay(creador.name, estilo: .cuerpoFuerte)
                                .lineLimit(1)
                            TextoRelay(descripcionCorta, estilo: .secundario)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(minHeight: Tactil.minimo)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(creador.name), \(creador.category). Ver su perfil.")
                .accessibilityAddTraits(.isButton)

                Button(action: onSeguir) {
                    TextoRelay(
                        siguiendo ? "Siguiendo" : "Seguir",
                        estilo: .secundario,
                        color: siguiendo ? paleta.acentoTexto : paleta.texto
                    )
                    .padding(.horizontal, Espacio.md)
                    .frame(minWidth: 100, minHeight: Tactil.minimo)
                    .background(siguiendo ? paleta.acento : .clear)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .strokeBorder(siguiendo ? paleta.acento : paleta.borde, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    siguiendo
                        ? "Dejar de recibir avisos de \(creador.name)"
                        : "Recibir avisos de \(creador.name)"
                )
            }
            .padding(.vertical, Espacio.sm)

            Divider().background(paleta.borde)
        }
    }

    private var descripcionCorta: String {
        let n = creador.platforms.count
        let tema = creador.category.prefix(1).uppercased() + creador.category.dropFirst()
        return "\(tema) · \(n) \(n == 1 ? "lugar" : "lugares") donde publica"
    }
}

struct Avatar: View {
    let url: String?
    let nombre: String
    var tamano: CGFloat = 52

    @Environment(\.paleta) private var paleta

    var body: some View {
        Group {
            if let url, let destino = URL(string: url) {
                AsyncImage(url: destino) { fase in
                    switch fase {
                    case .success(let imagen):
                        imagen.resizable().scaledToFill()
                    default:
                        inicial
                    }
                }
            } else {
                inicial
            }
        }
        .frame(width: tamano, height: tamano)
        .background(paleta.superficieAlta)
        .clipShape(Circle())
        // Decorativa: el nombre ya lo lee VoiceOver en el texto de al lado.
        .accessibilityHidden(true)
    }

    private var inicial: some View {
        TextoRelay(String(nombre.prefix(1)).uppercased(), estilo: .cuerpoFuerte, color: paleta.textoSuave)
    }
}

/// Pantalla vacía: siempre dice qué hacer, nunca solo "no hay nada".
struct Vacio<Accion: View>: View {
    let titulo: String
    let mensaje: String
    @ViewBuilder var accion: () -> Accion

    var body: some View {
        VStack(spacing: Espacio.sm) {
            TextoRelay(titulo, estilo: .seccion)
            TextoRelay(mensaje, estilo: .cuerpo)
                .multilineTextAlignment(.center)
            accion().padding(.top, Espacio.lg)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(Espacio.xl)
    }
}

extension Vacio where Accion == EmptyView {
    init(titulo: String, mensaje: String) {
        self.init(titulo: titulo, mensaje: mensaje) { EmptyView() }
    }
}
