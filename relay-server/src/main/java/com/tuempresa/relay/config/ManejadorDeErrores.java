package com.tuempresa.relay.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Respuestas de error consistentes.
 *
 * Las apps y el panel leen el campo {@code message} y lo muestran tal cual al
 * usuario. Por eso los mensajes estan escritos en lenguaje llano y no como
 * trazas tecnicas: alguien de 70 anios va a leer esto en la pantalla de su
 * telefono.
 *
 * Del lado del servidor si registramos el detalle completo.
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    private Map<String, Object> cuerpo(HttpStatus estado, String mensaje, HttpServletRequest peticion) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "status", estado.value(),
                "error", estado.getReasonPhrase(),
                "message", mensaje,
                "path", peticion.getRequestURI()
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> deEstado(
            ResponseStatusException e, HttpServletRequest peticion
    ) {
        HttpStatus estado = HttpStatus.valueOf(e.getStatusCode().value());
        String mensaje = e.getReason() != null ? e.getReason() : estado.getReasonPhrase();
        return ResponseEntity.status(estado).body(cuerpo(estado, mensaje, peticion));
    }

    /**
     * Ruta inexistente.
     *
     * Sin este manejador, el catch-all de abajo convertia un 404 legitimo en
     * un 500 y ademas escupia una traza completa en el registro. Un 404 no es
     * un fallo del servidor: es una peticion a algo que no esta.
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<Map<String, Object>> deRutaInexistente(
            Exception e, HttpServletRequest peticion
    ) {
        log.debug("Ruta no encontrada: {}", peticion.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(cuerpo(HttpStatus.NOT_FOUND, "Esa ruta no existe.", peticion));
    }

    /** Errores de validacion de @Valid, agrupados en una sola frase legible. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> deValidacion(
            MethodArgumentNotValidException e, HttpServletRequest peticion
    ) {
        String mensaje = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() != null
                        ? error.getDefaultMessage()
                        : error.getField() + " no es valido")
                .distinct()
                .collect(Collectors.joining(" "));

        return ResponseEntity.badRequest()
                .body(cuerpo(HttpStatus.BAD_REQUEST, mensaje, peticion));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> deEstadoIlegal(
            IllegalStateException e, HttpServletRequest peticion
    ) {
        log.error("Estado invalido en {}", peticion.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(cuerpo(HttpStatus.INTERNAL_SERVER_ERROR,
                        e.getMessage() != null ? e.getMessage() : "Error interno.", peticion));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> deCualquierCosa(
            Exception e, HttpServletRequest peticion
    ) {
        // Nunca devolvemos el mensaje crudo de una excepcion inesperada: puede
        // filtrar rutas internas, nombres de colecciones o fragmentos de
        // consulta. El detalle va al registro, no a la pantalla.
        log.error("Error no controlado en {}", peticion.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(cuerpo(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Algo fallo de nuestro lado. Intentalo de nuevo en un momento.", peticion));
    }
}
