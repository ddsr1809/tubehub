package com.tuempresa.relay.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * El Admin SDK ignora las Firestore Security Rules por diseño: entra conµ
 * privilegios totales. Eso significa que toda la validación de permisos ocurreµ
 * en este servidor, no en la base de datos. Las reglas siguen protegiendo el
 * acceso directo desde las apps móviles, que es donde importan.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getApps().get(0);
        }

        FirebaseOptions opciones = FirebaseOptions.builder()
                .setCredentials(credenciales())
                .build();

        return FirebaseApp.initializeApp(opciones);
    }

    /**
     * Dos caminos: un archivo de clave de servicio, o las credenciales del
     * propio entorno de Google Cloud.
     *
     * Fuera de Google Cloud siempre hace falta el archivo. El error original
     * del SDK cuando no lo encuentra ("Your default credentials were not
     * found") no dice qué variable falta ni dónde ponerla, así que lo
     * traducimos a algo accionable.
     */
    private GoogleCredentials credenciales() throws IOException {
        String ruta = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");

        if (ruta != null && !ruta.isBlank()) {
            if (!Files.exists(Path.of(ruta))) {
                throw new IllegalStateException("""
                        GOOGLE_APPLICATION_CREDENTIALS apunta a un archivo que no existe:
                          %s
                        Descarga la clave desde la consola de Firebase:
                          Configuracion del proyecto > Cuentas de servicio > Generar clave privada
                        """.formatted(ruta));
            }

            try (FileInputStream flujo = new FileInputStream(ruta)) {
                GoogleCredentials credenciales = GoogleCredentials.fromStream(flujo);
                log.info("Credenciales de Firebase leidas de {}", ruta);
                return credenciales;
            }
        }

        // Sin variable: solo funciona dentro de Google Cloud, donde las
        // credenciales vienen del propio servicio.
        try {
            GoogleCredentials credenciales = GoogleCredentials.getApplicationDefault();
            log.info("Credenciales de Firebase tomadas del entorno de Google Cloud");
            return credenciales;

        } catch (IOException e) {
            throw new IllegalStateException("""

                    -------------------------------------------------------------
                    No hay credenciales de Firebase.

                    Si ejecutas desde IntelliJ, el archivo .env NO se lee solo.
                    Hay que declarar las variables en la configuracion de
                    ejecucion:

                      Run > Edit Configurations... > RelayApplication
                      > campo "Environment variables"

                    Como minimo:
                      GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/service-account.json

                    El archivo se descarga de la consola de Firebase:
                      Configuracion del proyecto > Cuentas de servicio
                      > Generar clave privada

                    Desde la terminal, en su lugar:
                      export $(grep -v '^#' .env | xargs) && ./gradlew bootRun
                    -------------------------------------------------------------
                    """, e);
        }
    }

    @Bean
    public Firestore firestore(FirebaseApp app) {
        return FirestoreClient.getFirestore(app);
    }

    @Bean
    public FirebaseAuth firebaseAuth(FirebaseApp app) {
        return FirebaseAuth.getInstance(app);
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp app) {
        return FirebaseMessaging.getInstance(app);
    }

    /**
     * Cliente HTTP compartido para el hub de WebSub, la Data API de YouTube y
     * el endpoint de revocacion de Apple.
     *
     * Los tiempos de espera son cortos a proposito: una llamada colgada
     * retiene un hilo, y el hub reintenta si tardamos demasiado en responder.
     */
    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder().requestFactory(fabrica).build();
    }
}

