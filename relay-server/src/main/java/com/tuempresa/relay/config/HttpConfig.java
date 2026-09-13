package com.tuempresa.relay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class HttpConfig {

    /**
     * Cliente HTTP compartido para el hub de WebSub, la Data API de YouTube,
     * FCM y el endpoint de revocacion de Apple.
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
