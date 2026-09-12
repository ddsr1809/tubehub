package com.tuempresa.relay.websub;

import com.tuempresa.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Webhook de WebSub. Esta es la URL que se registra como hub.callback y la que
 * va en RELAY_URL_PUBLICA.
 *
 * Es público por necesidad: Google tiene que poder alcanzarlo sin credenciales.
 * La autenticidad se comprueba con la firma HMAC del cuerpo, no con un token.
 */
@RestController
public class WebSubController {

    private static final Logger log = LoggerFactory.getLogger(WebSubController.class);

    private final WebSubService servicio;
    private final VerificadorDeFirma verificador;
    private final RelayProperties config;

    public WebSubController(WebSubService servicio, VerificadorDeFirma verificador,
                            RelayProperties config) {
        this.servicio = servicio;
        this.verificador = verificador;
        this.config = config;
    }

    /**
     * Verificación de intención.
     *
     * Hay que responder 200 con el valor exacto de hub.challenge como texto
     * plano. Cualquier otra cosa —JSON, una redirección, otro código— hace que
     * el hub descarte la suscripción sin avisar, y las notificaciones dejarían
     * de llegar sin ningún error visible.
     */
    @GetMapping(value = "/websub", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verificar(
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "hub.mode", required = false) String modo,
            @RequestParam(name = "hub.topic", required = false) String topic,
            @RequestParam(name = "hub.challenge", required = false) String challenge,
            @RequestParam(name = "hub.lease_seconds", required = false, defaultValue = "0") long lease
    ) {
        if (!config.websub().tokenCallback().equals(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No encontrado");
        }
        if (modo == null || topic == null || challenge == null) {
            return ResponseEntity.badRequest().body("Faltan parámetros de verificación");
        }

        String respuesta = servicio.verificarIntencion(modo, topic, challenge, lease);
        if (respuesta == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No solicitado");
        }

        return ResponseEntity.ok(respuesta);
    }

    /**
     * Llegada de contenido.
     *
     * El cuerpo se recibe como byte[] crudo a propósito: el HMAC se calcula
     * sobre los bytes exactos que envió Google. Si dejáramos que Spring
     * deserializara el XML primero, cualquier normalización de espacios o de
     * codificación invalidaría la firma.
     */
    @PostMapping("/websub")
    public ResponseEntity<Void> recibir(
            @RequestParam(name = "token", required = false) String token,
            @RequestHeader(name = "X-Hub-Signature", required = false) String firma,
            @RequestBody byte[] cuerpo
    ) {
        if (!config.websub().tokenCallback().equals(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (!verificador.esValida(cuerpo, firma, config.websub().secreto())) {
            log.warn("Firma X-Hub-Signature inválida; carga descartada");
            // 200 a propósito: si respondemos error, el hub reintenta y acaba
            // cancelando la suscripción. Descartamos en silencio.
            return ResponseEntity.ok().build();
        }

        // Procesamos antes de responder. El enriquecimiento tarda unos cientos
        // de milisegundos y el hub tolera esa espera sin problema; hacerlo en
        // segundo plano nos expondría a que el contenedor se apague a medias.
        servicio.procesarAviso(cuerpo);

        return ResponseEntity.noContent().build();
    }
}
