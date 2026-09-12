package com.tuempresa.relay.websub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Autenticación de las cargas útiles del hub.
 *
 * Sin esta comprobación, cualquiera que conozca la URL del webhook puede
 * inyectar avisos falsos a los usuarios de la app. Es la única barrera que hay,
 * porque el endpoint tiene que ser público para que Google pueda alcanzarlo.
 */
@Component
public class VerificadorDeFirma {

    private static final Logger log = LoggerFactory.getLogger(VerificadorDeFirma.class);

    private static final Map<String, String> ALGORITMOS = Map.of(
            "sha1", "HmacSHA1",
            "sha256", "HmacSHA256",
            "sha384", "HmacSHA384",
            "sha512", "HmacSHA512"
    );

    public boolean esValida(byte[] cuerpo, String cabecera, String secreto) {
        if (cuerpo == null || cuerpo.length == 0) return false;
        if (cabecera == null || cabecera.isBlank()) return false;
        if (secreto == null || secreto.isBlank()) {
            log.error("WEBSUB_SECRETO sin configurar: no se puede validar ninguna firma");
            return false;
        }

        String[] partes = cabecera.split("=", 2);
        if (partes.length != 2) return false;

        String algoritmo = ALGORITMOS.get(partes[0].toLowerCase());
        if (algoritmo == null) {
            log.warn("Algoritmo de firma no permitido: {}", partes[0]);
            return false;
        }

        String propia;
        try {
            Mac mac = Mac.getInstance(algoritmo);
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), algoritmo));
            propia = HexFormat.of().formatHex(mac.doFinal(cuerpo));
        } catch (Exception e) {
            log.error("No se pudo calcular el HMAC", e);
            return false;
        }

        // Comparación en tiempo constante. Con un equals normal, el tiempo de
        // respuesta revelaría cuántos caracteres del hash son correctos, y un
        // atacante podría reconstruir la firma byte a byte.
        return MessageDigest.isEqual(
                propia.getBytes(StandardCharsets.UTF_8),
                partes[1].trim().getBytes(StandardCharsets.UTF_8)
        );
    }
}
