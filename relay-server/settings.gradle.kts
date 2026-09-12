plugins {
    // Si el JDK 21 no está instalado, Gradle lo descarga solo en lugar de
    // fallar con "No matching toolchains found". Ahorra el rato de buscar e
    // instalar un JDK a mano, sobre todo si ya tienes el 17 para Android.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "relay-server"
