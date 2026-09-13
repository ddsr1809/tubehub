plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.tuempresa"
version = "0.1.0"
description = "Servidor de seguimiento de creadores"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

    // Base de datos propia. Nada sale de tu VPS.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // JWT: emitimos los nuestros y verificamos los de Google y Apple contra
    // sus claves publicas. Nimbus trae el cliente JWKS remoto con cache.
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")

    // Lo unico que sigue siendo de Google: el token OAuth para llamar a FCM.
    // Son dos jars, no los ~50 que arrastraba firebase-admin.
    implementation("com.google.auth:google-auth-library-oauth2-http:1.30.1")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Conserva los nombres de parametros para que Spring enlace
    // @RequestParam y los constructores de records sin anotaciones extra.
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("relay-server.jar")
}
