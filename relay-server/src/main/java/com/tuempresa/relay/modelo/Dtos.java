package com.tuempresa.relay.modelo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Objetos que entran y salen por la API.
 *
 * Aquí sí usamos records: los construye Jackson, no Firestore, y son
 * inmutables por definición. Los mensajes de validación están escritos para
 * que puedan mostrarse tal cual en la pantalla del usuario.
 */
public final class Dtos {

    private Dtos() {}

    // -------------------------------------------------------------------------
    // Peticiones
    // -------------------------------------------------------------------------

    public record GuardarCreador(
            String id,

            @NotBlank(message = "El creador necesita un nombre.")
            @Size(min = 2, max = 60, message = "El nombre debe tener entre 2 y 60 caracteres.")
            String name,

            String category,

            @Size(max = 600, message = "La descripción no puede pasar de 600 caracteres.")
            String bio,

            String photoUrl,
            Map<String, Conexion> platforms,
            Boolean active
    ) {
        /** Jackson deja los campos ausentes en null; aquí les damos sentido. */
        public String categoriaOtros() {
            return (category == null || category.isBlank()) ? "otros" : category;
        }

        public boolean estaActivo() {
            return active == null || active;
        }

        public Map<String, Conexion> plataformasSeguras() {
            return platforms != null ? platforms : Map.of();
        }
    }

    public record MoverContenido(
            @NotBlank(message = "Falta el enlace nuevo.")
            @Pattern(regexp = "^https://.*", message = "El enlace debe empezar por https.")
            String url,

            String platform,
            Boolean avisar
    ) {
        public String plataformaDestino() {
            return (platform == null || platform.isBlank()) ? "web" : platform;
        }

        public boolean debeAvisar() {
            return avisar == null || avisar;
        }
    }

    public record Reporte(
            String videoId,
            String creatorId,

            @Size(max = 500, message = "El motivo es demasiado largo.")
            String reason
    ) {
        public String motivoSeguro() {
            return (reason == null || reason.isBlank()) ? "enlace_roto" : reason;
        }
    }

    public record TokenApple(
            @NotBlank(message = "Falta el código de autorización de Apple.")
            String authorizationCode
    ) {}

    // -------------------------------------------------------------------------
    // Respuestas
    // -------------------------------------------------------------------------

    public record RespuestaSimple(boolean ok, String mensaje) {
        public static RespuestaSimple de(String mensaje) {
            return new RespuestaSimple(true, mensaje);
        }
    }

    public record CreadorGuardado(String id, String avisoSuscripcion) {}

    public record DatosDeCanal(
            String channelId,
            String title,
            String description,
            String photoUrl,
            String handle,
            String subscriberCount
    ) {}

    public record ResultadoRenovacion(int renovados, int fallidos, List<String> errores) {}
}
