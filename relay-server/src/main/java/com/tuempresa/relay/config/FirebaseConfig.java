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
import java.time.Duration;

/**
 * El Admin SDK ignora las Firestore Security Rules por diseño: entra con
 * privilegios totales. Eso significa que toda la validación de permisos ocurre
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

        // Dentro de Google Cloud (Cloud Run, GKE, Compute Engine) las
        // credenciales se toman del entorno y no hace falta ningún archivo.
        // Fuera, apunta GOOGLE_APPLICATION_CREDENTIALS al service-account.json.
        String ruta = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        GoogleCredentials credenciales;

        if (ruta != null && !ruta.isBlank()) {
            log.info("Credenciales de Firebase leídas de {}", ruta);
            try (FileInputStream flujo = new FileInputStream(ruta)) {
                credenciales = GoogleCredentials.fromStream(flujo);
            }
        } else {
            log.info("Credenciales de Firebase tomadas del entorno de Google Cloud");
            credenciales = GoogleCredentials.getApplicationDefault();
        }

        FirebaseOptions opciones = FirebaseOptions.builder()
                .setCredentials(credenciales)
                .build();

        return FirebaseApp.initializeApp(opciones);
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
     * el endpoint de revocación de Apple.
     *
     * Los tiempos de espera son cortos a propósito: una llamada colgada
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
