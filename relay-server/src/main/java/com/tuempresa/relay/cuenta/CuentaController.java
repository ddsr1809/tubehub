package com.tuempresa.relay.cuenta;

import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.auth.FirebaseAuth;
import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.Colecciones;
import com.tuempresa.relay.modelo.Dtos;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CuentaController {

    private static final Logger log = LoggerFactory.getLogger(CuentaController.class);

    private final Firestore db;
    private final FirebaseAuth auth;
    private final AppleService apple;

    public CuentaController(Firestore db, FirebaseAuth auth, AppleService apple) {
        this.db = db;
        this.auth = auth;
        this.apple = apple;
    }

    /**
     * Guarda el refresh token de Apple en el momento del login.
     *
     * Sin este paso no se puede revocar después. La colección privateTokens
     * está cerrada a todo el mundo en las Security Rules: solo entra el Admin
     * SDK desde este servidor.
     */
    @PostMapping("/apple/token")
    public Dtos.RespuestaSimple guardarTokenApple(@Valid @RequestBody Dtos.TokenApple peticion) {
        String uid = Sesion.exigirSesion();

        String refreshToken = apple.canjearCodigo(peticion.authorizationCode());
        if (refreshToken == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo completar el inicio de sesión con Apple.");
        }

        Map<String, Object> datos = new HashMap<>();
        datos.put("refreshToken", refreshToken);
        datos.put("uid", uid);
        datos.put("guardadoEn", Timestamp.now());

        esperar(db.collection(Colecciones.TOKENS_PRIVADOS).document(uid)
                .set(datos, SetOptions.merge()));

        return Dtos.RespuestaSimple.de("Sesión guardada.");
    }

    /**
     * Borrado completo: revoca Apple, limpia Firestore y elimina la cuenta de
     * Auth. Es irreversible y no pasa por soporte, como exige la Guideline
     * 5.1.1(v). No es una desactivación ni una pausa.
     */
    @DeleteMapping("/cuenta")
    public Dtos.RespuestaSimple borrarCuenta() {
        String uid = Sesion.exigirSesion();

        // 1. Revocar el vínculo con Apple, si lo hay.
        DocumentReference tokenRef = db.collection(Colecciones.TOKENS_PRIVADOS).document(uid);
        DocumentSnapshot tokenDoc = esperar(tokenRef.get());
        String refreshToken = tokenDoc.exists() ? tokenDoc.getString("refreshToken") : null;

        if (refreshToken != null) {
            try {
                apple.revocar(refreshToken);
                log.info("Vínculo con Apple revocado para {}", uid);
            } catch (Exception e) {
                // Si Apple falla seguimos adelante con el borrado local: dejar
                // la cuenta a medio borrar sería peor para el usuario. Queda
                // registrado para reintentarlo desde el panel.
                log.error("Revocación en Apple fallida para {}", uid, e);

                Map<String, Object> incidencia = new HashMap<>();
                incidencia.put("uid", uid);
                incidencia.put("reason", "apple_revoke_failed");
                incidencia.put("detalle", recortar(e.getMessage(), 300));
                incidencia.put("resolved", false);
                incidencia.put("createdAt", Timestamp.now());
                db.collection(Colecciones.REPORTES).add(incidencia);
            }
        }

        // 2. Limpiar todo lo que este usuario dejó en la base de datos.
        WriteBatch lote = db.batch();
        lote.delete(db.collection(Colecciones.USUARIOS).document(uid));
        lote.delete(tokenRef);

        QuerySnapshot reportes = esperar(db.collection(Colecciones.REPORTES)
                .whereEqualTo("uid", uid)
                .limit(400)
                .get());
        reportes.getDocuments().forEach(doc -> lote.delete(doc.getReference()));

        esperar(lote.commit());

        // 3. Eliminar la identidad. Invalida cualquier sesión abierta.
        try {
            auth.deleteUser(uid);
        } catch (Exception e) {
            log.error("No se pudo eliminar el usuario {} de Firebase Auth", uid, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo borrar la cuenta. Inténtalo de nuevo en un momento.");
        }

        log.info("Cuenta {} eliminada por completo", uid);
        return Dtos.RespuestaSimple.de("Cuenta borrada.");
    }

    /**
     * Reporte de enlace roto.
     *
     * Es la puerta de entrada al sistema de redirección de emergencia: cuando
     * llegan reportes, el equipo puede apuntar ese contenido a otra plataforma
     * y avisar a toda la audiencia de una vez.
     */
    @PostMapping("/reportes")
    public Dtos.RespuestaSimple reportar(@Valid @RequestBody Dtos.Reporte peticion) {
        String uid = Sesion.exigirSesion();

        boolean sinVideo = peticion.videoId() == null || peticion.videoId().isBlank();
        boolean sinCreador = peticion.creatorId() == null || peticion.creatorId().isBlank();
        if (sinVideo && sinCreador) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el contenido reportado.");
        }

        Map<String, Object> reporte = new HashMap<>();
        reporte.put("uid", uid);
        reporte.put("videoId", peticion.videoId());
        reporte.put("creatorId", peticion.creatorId());
        reporte.put("reason", recortar(peticion.motivoSeguro(), 500));
        reporte.put("resolved", false);
        reporte.put("createdAt", Timestamp.now());

        esperar(db.collection(Colecciones.REPORTES).add(reporte));

        // Contador rápido para que el panel muestre los casos calientes primero.
        if (!sinVideo) {
            db.collection(Colecciones.VIDEOS).document(peticion.videoId())
                    .set(Map.of("reportes", FieldValue.increment(1)), SetOptions.merge());
        }

        return Dtos.RespuestaSimple.de("Gracias. Vamos a revisar ese enlace.");
    }

    // -------------------------------------------------------------------------

    private <T> T esperar(ApiFuture<T> futuro) {
        try {
            return futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operación de Firestore interrumpida", e);
        } catch (Exception e) {
            throw new IllegalStateException("Error de Firestore: " + e.getMessage(), e);
        }
    }

    private String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
