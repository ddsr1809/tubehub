package com.tuempresa.relay.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.tuempresa.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Optional;
import java.util.Set;

/**
 * Verificacion de identidad sin depender de Firebase Auth.
 *
 * Google y Apple publican sus claves publicas en un JWKS. Descargamos ese
 * conjunto, cacheamos, y validamos la firma del token que nos manda la app
 * junto con el emisor, la audiencia y la caducidad.
 *
 * Es exactamente lo que hacia Firebase Auth por debajo. Hacerlo aqui nos quita
 * una dependencia externa y no cuesta nada.
 */
@Service
public class VerificadorIdentidad {

    private static final Logger log = LoggerFactory.getLogger(VerificadorIdentidad.class);

    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_EMISORES =
            Set.of("https://accounts.google.com", "accounts.google.com");

    private static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_EMISOR = "https://appleid.apple.com";

    private final RelayProperties config;

    /**
     * Los procesadores se construyen una vez. JWKSourceBuilder cachea las
     * claves y las refresca solo cuando aparece un kid desconocido, asi que no
     * bombardeamos a Google en cada inicio de sesion.
     */
    private ConfigurableJWTProcessor<SecurityContext> google;
    private ConfigurableJWTProcessor<SecurityContext> apple;

    public VerificadorIdentidad(RelayProperties config) {
        this.config = config;
    }

    /** Identidad extraida de un token de proveedor. */
    public record Identidad(String proveedor, String sub, String email, String nombre) {}

    // -------------------------------------------------------------------------
    // Google
    // -------------------------------------------------------------------------

    public Optional<Identidad> verificarGoogle(String idToken) {
        if (config.google().clientId().isBlank()) {
            log.error("GOOGLE_CLIENT_ID sin configurar: no se puede verificar ningún token de Google");
            return Optional.empty();
        }

        try {
            if (google == null) {
                google = construir(GOOGLE_JWKS, null, config.google().clientId());
            }

            JWTClaimsSet claims = google.process(idToken, null);

            // La audiencia la valida el procesador; el emisor lo comprobamos
            // aquí porque Google usa dos formas equivalentes.
            if (!GOOGLE_EMISORES.contains(claims.getIssuer())) {
                log.warn("Token de Google con emisor inesperado: {}", claims.getIssuer());
                return Optional.empty();
            }

            return Optional.of(new Identidad(
                    "google",
                    claims.getSubject(),
                    (String) claims.getClaim("email"),
                    (String) claims.getClaim("name")
            ));

        } catch (Exception e) {
            log.warn("Token de Google rechazado: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Apple
    // -------------------------------------------------------------------------

    public Optional<Identidad> verificarApple(String identityToken) {
        if (config.apple().bundleId().isBlank()) {
            log.error("APPLE_BUNDLE_ID sin configurar: no se puede verificar ningún token de Apple");
            return Optional.empty();
        }

        try {
            if (apple == null) {
                apple = construir(APPLE_JWKS, APPLE_EMISOR, config.apple().bundleId());
            }

            JWTClaimsSet claims = apple.process(identityToken, null);

            // Apple solo manda el correo en el primer inicio de sesión, y si
            // el usuario eligió ocultarlo llega un alias de privaterelay.
            // El 'sub' es lo único estable.
            return Optional.of(new Identidad(
                    "apple",
                    claims.getSubject(),
                    (String) claims.getClaim("email"),
                    null
            ));

        } catch (Exception e) {
            log.warn("Token de Apple rechazado: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------

    private ConfigurableJWTProcessor<SecurityContext> construir(
            String urlJwks, String emisor, String audiencia
    ) throws Exception {

        JWKSource<SecurityContext> claves = JWKSourceBuilder
                .create(new URL(urlJwks))
                .retrying(true)
                .build();

        DefaultJWTProcessor<SecurityContext> procesador = new DefaultJWTProcessor<>();
        procesador.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, claves));

        JWTClaimsSet esperado = emisor != null
                ? new JWTClaimsSet.Builder().issuer(emisor).audience(audiencia).build()
                : new JWTClaimsSet.Builder().audience(audiencia).build();

        // exp y iat se validan solos; exigimos además que sub esté presente.
        procesador.setJWTClaimsSetVerifier(
                new DefaultJWTClaimsVerifier<>(esperado, Set.of("sub", "exp")));

        return procesador;
    }
}
