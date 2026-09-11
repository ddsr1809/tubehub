import SwiftUI
import AuthenticationServices

struct AjustesVista: View {
    @EnvironmentObject private var directorio: DirectorioStore
    @EnvironmentObject private var autenticacion: AutenticacionStore
    @Environment(\.paleta) private var paleta

    @State private var confirmandoBorrado = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                TextoRelay("Ajustes", estilo: .titulo)
                    .padding(.bottom, Espacio.lg)

                seccionTamanoDeLetra
                seccionColores
                seccionCuenta
                seccionBorrado

                TextoRelay(
                    "Esta app no reproduce videos. Solo te avisa cuando alguien publica y te lleva a la app oficial donde está el contenido.",
                    estilo: .secundario
                )
                .padding(.top, Espacio.lg)
                .padding(.bottom, Espacio.xxl)
            }
            .padding(Espacio.md)
        }
        .background(paleta.fondo)
        .confirmationDialog(
            "Borrar tu cuenta",
            isPresented: $confirmandoBorrado,
            titleVisibility: .visible
        ) {
            Button("Borrar cuenta", role: .destructive) {
                Task { await autenticacion.borrarCuenta() }
            }
            Button("Cancelar", role: .cancel) { }
        } message: {
            Text("Se elimina tu cuenta y todo lo que guardaste: los creadores que sigues y tus preferencias. No se puede deshacer.")
        }
        .alert(item: $autenticacion.conflicto) { conflicto in
            // Colisión de cuentas: en vez de un código de error, explicamos qué
            // botón tocar. Este diálogo evita que alguien se quede fuera de su
            // propia cuenta al cambiar de teléfono.
            Alert(
                title: Text("Ese correo ya tiene cuenta"),
                message: Text(
                    "Tu correo \(conflicto.correo ?? "") ya se registró antes con otro método. Entra con ese método y juntamos las dos cuentas en una sola."
                ),
                primaryButton: .default(Text("Entrar con Apple")) {
                    Task {
                        await autenticacion.resolverConflicto(
                            usando: "apple",
                            presentando: Self.controladorVisible()
                        )
                    }
                },
                secondaryButton: .cancel(Text("Ahora no")) {
                    autenticacion.conflicto = nil
                }
            )
        }
    }

    // MARK: - Secciones

    private var seccionTamanoDeLetra: some View {
        Seccion("Tamaño de la letra") {
            ForEach(EscalaTexto.allCases) { escala in
                BotonGrande(
                    titulo: escala.etiqueta,
                    variante: directorio.perfil.escalaTexto == escala.rawValue ? .primario : .secundario
                ) {
                    Task { await directorio.guardarPreferencia("escalaTexto", escala.rawValue) }
                }
            }
            TextoRelay(
                "Esto se suma al tamaño de letra que ya tengas puesto en el iPhone. La pantalla se acomoda hasta el doble de grande sin cortar el texto.",
                estilo: .secundario
            )
        }
    }

    private var seccionColores: some View {
        Seccion("Colores") {
            BotonGrande(
                titulo: "Fondo oscuro",
                subtitulo: "Cansa menos la vista de noche",
                variante: directorio.perfil.tema == "oscuro" ? .primario : .secundario
            ) {
                Task { await directorio.guardarPreferencia("tema", "oscuro") }
            }
            BotonGrande(
                titulo: "Fondo claro",
                subtitulo: "Se lee mejor con mucha luz",
                variante: directorio.perfil.tema == "claro" ? .primario : .secundario
            ) {
                Task { await directorio.guardarPreferencia("tema", "claro") }
            }
            BotonGrande(
                titulo: "Como el iPhone",
                subtitulo: "Cambia solo según la hora o tus ajustes",
                variante: directorio.perfil.tema == "sistema" ? .primario : .secundario
            ) {
                Task { await directorio.guardarPreferencia("tema", "sistema") }
            }
        }
    }

    private var seccionCuenta: some View {
        Seccion("Tu cuenta") {
            if autenticacion.esAnonimo {
                TextoRelay(
                    "Estás usando la app como invitado. Funciona todo, pero si cambias de teléfono o borras la app, los creadores que sigues se pierden. Guarda tu cuenta para que te acompañen.",
                    estilo: .cuerpo
                )
                .padding(.bottom, Espacio.md)

                // Sign in with Apple va primero y con la variante primaria.
                // La Guideline 4.8 exige que si ofrecemos Google, ofrezcamos
                // también el de Apple, y con la misma prominencia visual.
                BotonGrande(
                    titulo: "Guardar con Apple",
                    subtitulo: "Puedes ocultar tu correo real",
                    variante: .primario,
                    habilitado: !autenticacion.ocupado
                ) {
                    Task { await autenticacion.vincularConApple() }
                }

                BotonGrande(
                    titulo: "Guardar con Google",
                    variante: .secundario,
                    habilitado: !autenticacion.ocupado
                ) {
                    Task {
                        await autenticacion.vincularConGoogle(
                            presentando: Self.controladorVisible()
                        )
                    }
                }
            } else {
                TextoRelay(
                    "Tu cuenta está guardada" + (autenticacion.correo.map { " como \($0)" } ?? "") + ".",
                    estilo: .cuerpo
                )
                .padding(.bottom, Espacio.md)

                BotonGrande(titulo: "Cerrar sesión", variante: .secundario) {
                    Task { await autenticacion.cerrarSesion() }
                }
            }
        }
    }

    private var seccionBorrado: some View {
        Seccion("Borrar todo") {
            TextoRelay(
                "Borrar la cuenta elimina de verdad todos tus datos de nuestros servidores. No es una pausa ni una desactivación.",
                estilo: .cuerpo
            )
            .padding(.bottom, Espacio.md)

            BotonGrande(
                titulo: "Borrar mi cuenta",
                variante: .peligro,
                habilitado: !autenticacion.ocupado
            ) {
                confirmandoBorrado = true
            }
        }
    }

    /// SwiftUI no expone el UIViewController que GoogleSignIn necesita para
    /// presentar su hoja, así que hay que buscarlo en la jerarquía de UIKit.
    @MainActor
    static func controladorVisible() -> UIViewController {
        let escena = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first
        return escena?.windows.first(where: \.isKeyWindow)?.rootViewController
            ?? UIViewController()
    }
}

private struct Seccion<Contenido: View>: View {
    let titulo: String
    @ViewBuilder var contenido: () -> Contenido
    @Environment(\.paleta) private var paleta

    init(_ titulo: String, @ViewBuilder contenido: @escaping () -> Contenido) {
        self.titulo = titulo
        self.contenido = contenido
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider().background(paleta.borde)
            TextoRelay(titulo, estilo: .seccion)
                .padding(.top, Espacio.lg)
                .padding(.bottom, Espacio.md)
            contenido()
        }
        .padding(.top, Espacio.lg)
    }
}
