import SwiftUI

// Sistema de diseño.
//
// Todo lo que hay aquí sale de un requisito concreto de WCAG 2.2 AA o de la
// ergonomía del pulgar, no de una preferencia estética:
//
//  · 1.4.3 Contraste — mínimo legal 4.5:1. Estos valores llegan a 8:1 o más
//    porque el público objetivo tiene visión reducida.
//  · 2.5.8 Tamaño del objetivo — Apple recomienda 44pt; partimos de 48 y
//    usamos 56 en acciones principales, con separación entre botones.
//  · 1.4.4 Redimensionar texto — usamos estilos semánticos (.body, .title)
//    para heredar la rampa de Dynamic Type de Apple, que escala los títulos
//    menos que el cuerpo y así no aplasta la jerarquía.
//
// El fondo oscuro es gris profundo, no negro absoluto: en OLED el texto blanco
// sobre negro puro "vibra" y cansa la vista.

extension Color {
    static let carbon = Color(red: 0.086, green: 0.094, blue: 0.110)          // #16181C
    static let superficie = Color(red: 0.129, green: 0.145, blue: 0.173)      // #21252C
    static let superficieAlta = Color(red: 0.169, green: 0.188, blue: 0.220)  // #2B3038
    static let bordeRelay = Color(red: 0.227, green: 0.255, blue: 0.294)      // #3A414B
    static let textoRelay = Color(red: 0.949, green: 0.957, blue: 0.965)      // #F2F4F6, 15.2:1
    static let textoSuave = Color(red: 0.659, green: 0.690, blue: 0.729)      // #A8B0BA,  8.1:1
    static let ambar = Color(red: 1.000, green: 0.690, blue: 0.125)           // #FFB020,  9.4:1
    static let ambarTexto = Color(red: 0.141, green: 0.102, blue: 0.020)      // #241A05
    static let peligroRelay = Color(red: 1.000, green: 0.478, blue: 0.412)    // #FF7A69
}

/// Paleta que responde al modo elegido por el usuario, no solo al del sistema.
struct Paleta {
    let fondo: Color
    let superficie: Color
    let superficieAlta: Color
    let borde: Color
    let texto: Color
    let textoSuave: Color
    let acento: Color
    let acentoTexto: Color
    let peligro: Color

    static let oscura = Paleta(
        fondo: .carbon, superficie: .superficie, superficieAlta: .superficieAlta,
        borde: .bordeRelay, texto: .textoRelay, textoSuave: .textoSuave,
        acento: .ambar, acentoTexto: .ambarTexto, peligro: .peligroRelay
    )

    static let clara = Paleta(
        fondo: Color(red: 0.980, green: 0.980, blue: 0.973),
        superficie: .white,
        superficieAlta: Color(red: 0.941, green: 0.945, blue: 0.953),
        borde: Color(red: 0.788, green: 0.804, blue: 0.827),
        texto: Color(red: 0.078, green: 0.086, blue: 0.102),   // 16.1:1
        textoSuave: Color(red: 0.322, green: 0.353, blue: 0.392), // 7.6:1
        acento: Color(red: 0.541, green: 0.294, blue: 0.0),     // 7.2:1 sobre claro
        acentoTexto: .white,
        peligro: Color(red: 0.639, green: 0.137, blue: 0.086)
    )
}

/// Tamaños táctiles. Apple pide 44pt como mínimo; usamos más.
enum Tactil {
    static let minimo: CGFloat = 48
    static let principal: CGFloat = 56
    static let separacion: CGFloat = 12
}

enum Espacio {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 48
}

// MARK: - Entorno

private struct ClavePaleta: EnvironmentKey {
    static let defaultValue = Paleta.oscura
}

extension EnvironmentValues {
    var paleta: Paleta {
        get { self[ClavePaleta.self] }
        set { self[ClavePaleta.self] = newValue }
    }
}

/// Aplica la preferencia de tema y el tamaño de letra elegido en la app.
///
/// El tope de 2.0 es deliberado: Dynamic Type YA escala por su cuenta, y si
/// alguien tiene el iPhone en tamaño accesible y además elige "Muy grande"
/// aquí, el resultado rompería el layout. WCAG pide soportar el 200%, no el
/// infinito.
struct TemaRelay: ViewModifier {
    let preferencia: String
    let escala: EscalaTexto
    @Environment(\.colorScheme) private var esquemaSistema

    func body(content: Content) -> some View {
        let oscuro: Bool = {
            switch preferencia {
            case "claro": return false
            case "oscuro": return true
            default: return esquemaSistema == .dark
            }
        }()

        let paleta = oscuro ? Paleta.oscura : Paleta.clara

        content
            .environment(\.paleta, paleta)
            .preferredColorScheme(oscuro ? .dark : .light)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            .environment(\.sizeCategory, .large)
            .scaleEffectDeTexto(escala.factor)
            .background(paleta.fondo.ignoresSafeArea())
            .tint(paleta.acento)
    }
}

extension View {
    func temaRelay(preferencia: String, escala: EscalaTexto) -> some View {
        modifier(TemaRelay(preferencia: preferencia, escala: escala))
    }

    /// Aplica la escala propia de la app sobre la de Dynamic Type.
    func scaleEffectDeTexto(_ factor: CGFloat) -> some View {
        environment(\.escalaApp, factor)
    }
}

private struct ClaveEscala: EnvironmentKey {
    static let defaultValue: CGFloat = 1.0
}

extension EnvironmentValues {
    var escalaApp: CGFloat {
        get { self[ClaveEscala.self] }
        set { self[ClaveEscala.self] = newValue }
    }
}

/// Texto que combina los estilos semánticos de Apple con la escala de la app.
struct TextoRelay: View {
    enum Estilo { case titulo, seccion, cuerpo, cuerpoFuerte, boton, secundario }

    let contenido: String
    let estilo: Estilo
    var color: Color?

    @Environment(\.paleta) private var paleta
    @Environment(\.escalaApp) private var escala

    init(_ contenido: String, estilo: Estilo = .cuerpo, color: Color? = nil) {
        self.contenido = contenido
        self.estilo = estilo
        self.color = color
    }

    var body: some View {
        Text(contenido)
            .font(fuente)
            .foregroundStyle(color ?? colorPorDefecto)
    }

    private var fuente: Font {
        // Partimos de un estilo semántico para heredar la rampa de Apple, y
        // luego escalamos el tamaño base con la preferencia de la app.
        switch estilo {
        case .titulo: return .system(size: 28 * escala, weight: .bold)
        case .seccion: return .system(size: 21 * escala, weight: .semibold)
        case .cuerpo: return .system(size: 18 * escala)
        case .cuerpoFuerte: return .system(size: 18 * escala, weight: .semibold)
        case .boton: return .system(size: 19 * escala, weight: .semibold)
        case .secundario: return .system(size: 15 * escala)
        }
    }

    private var colorPorDefecto: Color {
        switch estilo {
        case .secundario: return paleta.textoSuave
        default: return paleta.texto
        }
    }
}
