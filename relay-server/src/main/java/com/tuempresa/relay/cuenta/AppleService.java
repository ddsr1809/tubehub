package com.tuempresa.relay.cuenta;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.tuempresa.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Integración con Apple.
 *
 * La Guideline 5.1.1(v) exige que toda app capaz de crear cuentas ofrezca el
 * borrado completo desde dentro de la app. Y si la cuenta se creó con Sign in
 * with Apple, hay que revocar además el token federado. Sin eso, el revisor
 * rechaza la app, y es un rechazo del que no se sale con explicaciones.
 *
 * Solo hace falta si vas a publicar en la App Store. Si de momento solo
 * trabajas en Android, puedes dejar estas variables vacías y el servidor
 * arranca igual.
 */
@Service
public class AppleService {

    private static final Logger log = LoggerFactory.getLogger(AppleService.class);

    private final RestClient http;
    private final RelayProperties config;

    public AppleService(RestClient http, RelayProperties config) {
        this.http = http;
        this.config = config;
    }

    /**
     * Client secret que exige Apple: un JWT firmado con ES256 usando la clave
     * .p8 del portal de desarrollador. Vive cinco minutos a propósito, porque
     * solo se usa para una petición.
     */
    private String clientSecret() {
        if (!config.apple().puedeRevocar()) {
            throw new IllegalStateException(
                    "Faltan credenciales de Apple (bundle-id, team-id, key-id, clave-privada).");
        }

        Instant ahora = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(config.apple().teamId())
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(300)))
                .audience("https://appleid.apple.com")
                .subject(config.apple().bundleId())
                .build();

        JWSHeader cabecera = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(config.apple().keyId())
                .build();

        try {
            SignedJWT jwt = new SignedJWT(cabecera, claims);
            jwt.sign(new ECDSASigner(leerClavePrivada()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el client secret de Apple", e);
        }
    }

    /**
     * El .p8 viene en formato PEM PKCS#8. Hay que quitar las cabeceras y
     * decodificar el base64. La variable de entorno suele traer los saltos de
     * línea escapados como \n literales, de ahí el primer replace.
     */
    private ECPrivateKey leerClavePrivada() {
        String pem = config.apple().clavePrivada()
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        try {
            byte[] bytes = Base64.getDecoder().decode(pem);
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "La clave privada de Apple no se pudo leer. Revisa que APPLE_CLAVE_PRIVADA "
                            + "tenga el contenido completo del archivo .p8.", e);
        }
    }

    /** Canjea el código de autorización por un refresh token de larga vida. */
    public String canjearCodigo(String codigo) {
        if (!config.apple().puedeRevocar()) {
            log.warn("Apple sin configurar; no se guardó el refresh token");
            return null;
        }

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("client_id", config.apple().bundleId());
        formulario.add("client_secret", clientSecret());
        formulario.add("code", codigo);
        formulario.add("grant_type", "authorization_code");

        try {
            Map<?, ?> respuesta = http.post()
                    .uri("https://appleid.apple.com/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulario)
                    .retrieve()
                    .body(Map.class);

            Object token = respuesta != null ? respuesta.get("refresh_token") : null;
            return token instanceof String cadena ? cadena : null;

        } catch (Exception e) {
            log.error("Canje del código de Apple fallido", e);
            return null;
        }
    }

    /** Extingue el vínculo federado. Sin esto, Apple rechaza la app. */
    public void revocar(String refreshToken) {
        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("client_id", config.apple().bundleId());
        formulario.add("client_secret", clientSecret());
        formulario.add("token", refreshToken);
        formulario.add("token_type_hint", "refresh_token");

        http.post()
                .uri("https://appleid.apple.com/auth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formulario)
                .retrieve()
                .toBodilessEntity();
    }
}
