package com.tuempresa.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Toda la configuración en un solo sitio y con tipos.
 *
 * Spring enlaza esto al arrancar, así que un secreto que falta se descubre en
 * el primer segundo con un mensaje claro, en lugar de tres días después
 * cuando el hub deja de entregar avisos en silencio.
 */
@ConfigurationProperties(prefix = "relay")
public record RelayProperties(

        @DefaultValue("") String urlPublica,
        @DefaultValue WebSub websub,
        @DefaultValue YouTube youtube,
        @DefaultValue Apple apple,
        @DefaultValue Renovacion renovacion,
        @DefaultValue Cors cors
) {

    public record WebSub(
            @DefaultValue("https://pubsubhubbub.appspot.com/subscribe") String hub,
            @DefaultValue("") String secreto,
            @DefaultValue("") String tokenCallback,
            @DefaultValue("864000") long leaseSegundos,
            @DefaultValue("6") long antiguedadMaximaHoras
    ) {}

    public record YouTube(@DefaultValue("") String apiKey) {}

    public record Apple(
            @DefaultValue("") String teamId,
            @DefaultValue("") String keyId,
            @DefaultValue("") String bundleId,
            @DefaultValue("") String clavePrivada
    ) {
        public boolean estaConfigurado() {
            return !teamId.isBlank() && !keyId.isBlank()
                    && !bundleId.isBlank() && !clavePrivada.isBlank();
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

    /** Feed Atom canónico de un canal. WebSub no acepta @handles como tema. */
    public static String feedDe(String channelId) {
        return "https://www.youtube.com/xml/feeds/videos.xml?channel_id=" + channelId;
    }
}
