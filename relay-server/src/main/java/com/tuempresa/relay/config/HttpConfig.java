package com.tuempresa.relay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpConfig {

    /**
     * Cliente HTTP compartido para el hub de WebSub, la Data API de YouTube,
     * FCM y el endpoint de revocacion de Apple.
     *
     * El hub de Google puede tardar 20 segundos o mas en contestar cuando esta
     * saturado, y entonces devuelve 503 con Retry-After. Con un plazo de 10s
     * cortabamos antes de oir la respuesta y lo registrabamos como rechazo,
     * que es un diagnostico equivocado y cuesta horas.
     *
     * JdkClientHttpRequestFactory usa java.net.http en lugar de
     * HttpURLConnection: no lleva bloques synchronized que fijen el hilo
     * virtual a su portador, cosa que importa en una maquina de un solo nucleo.
     */
    @Bean
    public RestClient restClient() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // El hub resuelve a IPv4 y IPv6; sin ruta v6 el intento se
                // queda colgado antes de reintentar por v4.
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(jdk);
        fabrica.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder().requestFactory(fabrica).build();
    }
}