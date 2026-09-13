package com.tuempresa.relay.cuenta;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Reporte;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Usuario;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CuentaController {

    private static final Logger log = LoggerFactory.getLogger(CuentaController.class);

    private final Repositorios.Usuarios usuarios;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Reportes reportes;
    private final AppleService apple;

    public CuentaController(Repositorios.Usuarios usuarios,
                            Repositorios.Publicaciones publicaciones,
                            Repositorios.Reportes reportes,
                            AppleService apple) {
        this.usuarios = usuarios;
        this.publicaciones = publicaciones;
        this.reportes = reportes;
        this.apple = apple;
    }

    /**
     * Reporte de enlace roto.
     *
     * Es la puerta de entrada al sistema de redirección de emergencia: cuando
     * llegan reportes, el equipo apunta ese contenido a otra plataforma y avisa
     * a toda la audiencia de una vez.
     */
    @PostMapping("/reportes")
    @Transactional
    public Dtos.RespuestaSimple reportar(@Valid @RequestBody Dtos.Reporte peticion) {
        UUID uid = Sesion.exigir();

        boolean sinVideo = peticion.videoId() == null || peticion.videoId().isBlank();
        if (sinVideo && peticion.creadorId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el contenido reportado.");
        }

        Reporte reporte = new Reporte();
        reporte.setUsuarioId(uid);
        reporte.setVideoId(peticion.videoId());
        reporte.setCreadorId(peticion.creadorId());
        reporte.setMotivo(recortar(peticion.motivoSeguro(), 500));
        reportes.save(reporte);

        // Contador rápido para que el panel muestre los casos calientes primero.
        if (!sinVideo) {
            publicaciones.sumarReporte(peticion.videoId());
        }

        return Dtos.RespuestaSimple.de("Gracias. Vamos a revisar ese enlace.");
    }

    /**
     * Borrado completo: revoca el vínculo con Apple si lo hay y elimina al
     * usuario. Es irreversible y no pasa por soporte, como exige la Guideline
     * 5.1.1(v). No es una desactivación ni una pausa.
     *
     * Los favoritos se van en cascada por la clave foránea; los reportes
     * quedan anónimos porque su referencia es ON DELETE SET NULL, así el
     * historial de moderación no se pierde al borrar una cuenta.
     */
    @DeleteMapping("/cuenta")
    @Transactional
    public Dtos.RespuestaSimple borrarCuenta() {
        UUID uid = Sesion.exigir();

        Usuario usuario = usuarios.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa cuenta ya no existe."));

        if (usuario.getAppleRefresh() != null && !usuario.getAppleRefresh().isBlank()) {
            try {
                apple.revocar(usuario.getAppleRefresh());
                log.info("Vínculo con Apple revocado para {}", uid);
            } catch (Exception e) {
                // Si Apple falla seguimos adelante: dejar la cuenta a medio
                // borrar sería peor para el usuario. Queda registrado para
                // reintentarlo desde el panel.
                log.error("Revocación en Apple fallida para {}", uid, e);

                Reporte incidencia = new Reporte();
                incidencia.setMotivo("apple_revoke_failed");
                incidencia.setDetalle(recortar(e.getMessage(), 300));
                reportes.save(incidencia);
            }
        }

        usuarios.delete(usuario);
        log.info("Cuenta {} eliminada por completo", uid);

        return Dtos.RespuestaSimple.de("Cuenta borrada.");
    }

    private String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
