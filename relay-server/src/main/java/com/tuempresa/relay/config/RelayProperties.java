package com.tuempresa.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Toda la configuracion en un sitio y con tipos. Spring la enlaza al arrancar,
 * asi que un secreto que falta se descubre en el primer segundo.
 */
@ConfigurationProperties(prefix = "relay")
public record RelayProperties(

        @DefaultValue("") String urlPublica,
        @DefaultValue Jwt jwt,
        @DefaultValue WebSub websub,
        @DefaultValue YouTube youtube,
        @DefaultValue Fcm fcm,
        @DefaultValue Google google,
        @DefaultValue Apple apple,
        @DefaultValue Renovacion renovacion,
        @DefaultValue Cors cors
) {

    public record Jwt(
            @DefaultValue("") String secreto,
            @DefaultValue("relay") String emisor,
            @DefaultValue("30") long diasValidez
    ) {}

    public record WebSub(
            @DefaultValue("https://pubsubhubbub.appspot.com/subscribe") String hub,
            @DefaultValue("") String secreto,
            @DefaultValue("") String tokenCallback,
            @DefaultValue("864000") long leaseSegundos,
            @DefaultValue("6") long antiguedadMaximaHoras
    ) {}

    public record YouTube(@DefaultValue("") String apiKey) {}

    public record Fcm(
            @DefaultValue("") String proyectoId,
            @DefaultValue("") String credenciales
    ) {
        public boolean estaConfigurado() {
            return !proyectoId.isBlank() && !credenciales.isBlank();
        }
    }

    public record Google(@DefaultValue("") String clientId) {}

    public record Apple(
            @DefaultValue("") String bundleId,
            @DefaultValue("") String teamId,
            @DefaultValue("") String keyId,
            @DefaultValue("") String clavePrivada
    ) {
        public boolean puedeRevocar() {
            return !bundleId.isBlank() && !teamId.isBlank()
                    && !keyId.isBlank() && !clavePrivada.isBlank();
        }
    }

    public record Renovacion(
            @DefaultValue("true") boolean programada,
            @DefaultValue("0 0 4 */4 * *") String cron,
            @DefaultValue("America/Hermosillo") String zona,
            @DefaultValue("") String tokenInterno
    ) {}

    public record Cors(@DefaultValue("") String origenes) {}

    /** URL exacta que registramos como hub.callback. */
    public String urlWebhook() {
        String base = urlPublica.endsWith("/")
                ? urlPublica.substring(0, urlPublica.length() - 1)
                : urlPublica;
        return base + "/websub?token=" + websub.tokenCallback();
    }

    /** Feed Atom canonico de un canal. WebSub no acepta @handles como tema. */
    public static String feedDe(String channelId) {
        return "https://www.youtube.com/xml/feeds/videos.xml?channel_id=" + channelId;
    }
}
