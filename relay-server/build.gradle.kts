plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.tuempresa"
version = "0.1.0"
description = "Servidor del Directorio de Creadores"

java {
    // Java 21 es LTS y es la versión en la que Spring Boot 3.4 está más
    // probado. Si prefieres reutilizar el JDK 17 que ya tienes para Android,
    // cambia el 21 por 17 aquí y quita spring.threads.virtual.enabled del
    // application.yml: los hilos virtuales solo existen a partir del 21.
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

    // Admin SDK: Firestore, Auth y FCM desde el servidor. Ignora las Firestore
    // Security Rules por diseño, así que toda validación de permisos ocurre
    // aquí dentro.
    implementation("com.google.firebase:firebase-admin:9.4.1")

    // Firma ES256 del client secret que Apple exige para revocar tokens.
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Conserva los nombres de los parámetros en el bytecode. Spring los
    // necesita para enlazar @RequestParam y los constructores de records sin
    // tener que repetir el nombre en cada anotación.
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("relay-server.jar")
}
