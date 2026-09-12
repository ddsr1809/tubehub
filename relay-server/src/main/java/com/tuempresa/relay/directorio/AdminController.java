package com.tuempresa.relay.directorio;

import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.PushService;
import com.tuempresa.relay.websub.WebSubService;
import com.tuempresa.relay.youtube.YouTubeClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Todo lo que hay bajo /api/admin exige ROLE_ADMIN, que viene del claim
 * {@code admin} del token de Firebase. Es el mismo claim que usan las Firestore
 * Security Rules, así que no hay dos fuentes de verdad sobre quién modera.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Pattern ID_CANAL = Pattern.compile("(UC[\\w-]{22})");
    private static final Pattern HANDLE = Pattern.compile("@([\\w.-]+)");

    private final Firestore db;
    private final FirebaseAuth auth;
    private final WebSubService websub;
    private final YouTubeClient youtube;
    private final PushService push;

    public AdminController(Firestore db, FirebaseAuth auth, WebSubService websub,
                           YouTubeClient youtube, PushService push) {
        this.db = db;
        this.auth = auth;
        this.websub = websub;
        this.youtube = youtube;
        this.push = push;
    }

    // -------------------------------------------------------------------------
    // Creadores
    // -------------------------------------------------------------------------

    @PostMapping("/creadores")
    public Dtos.CreadorGuardado guardar(@Valid @RequestBody Dtos.GuardarCreador peticion) {
        if (!Colecciones.CATEGORIAS.contains(peticion.categoriaOtros())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Categoría no válida: " + peticion.categoriaOtros());
        }

        Map<String, Object> conexiones = new HashMap<>();
        peticion.plataformasSeguras().forEach((plataforma, conexion) -> {
            if (!Colecciones.PLATAFORMAS.contains(plataforma)) return;
            if (conexion == null || conexion.getUrl() == null || conexion.getUrl().isBlank()) return;

            Map<String, Object> datos = new HashMap<>();
            datos.put("url", conexion.getUrl().trim());
            datos.put("handle", conexion.getHandle() != null ? conexion.getHandle().trim() : null);
            datos.put("channelId", conexion.getChannelId() != null ? conexion.getChannelId().trim() : null);
            conexiones.put(plataforma, datos);
        });

        DocumentReference ref = (peticion.id() != null && !peticion.id().isBlank())
                ? db.collection(Colecciones.CREADORES).document(peticion.id())
                : db.collection(Colecciones.CREADORES).document();

        Creador previo = null;
        if (peticion.id() != null && !peticion.id().isBlank()) {
            DocumentSnapshot doc = esperar(ref.get());
            if (doc.exists()) previo = doc.toObject(Creador.class);
        }

        Map<String, Object> datos = new HashMap<>();
        datos.put("name", peticion.name().trim());
        datos.put("category", peticion.categoriaOtros());
        datos.put("bio", recortar(peticion.bio(), 600));
        datos.put("photoUrl", peticion.photoUrl());
        datos.put("platforms", conexiones);
        datos.put("active", peticion.estaActivo());
        datos.put("updatedAt", Timestamp.now());
        if (previo == null) datos.put("createdAt", Timestamp.now());

        esperar(ref.set(datos, SetOptions.merge()));

        // Sincronizar WebSub si el canal cambió o si se activó o desactivó.
        Map<String, Object> youtubeConexion = castearMapa(conexiones.get("youtube"));
        String canalNuevo = youtubeConexion != null ? (String) youtubeConexion.get("channelId") : null;
        String canalPrevio = previo != null ? previo.getCanalDeYouTube() : null;

        try {
            if (canalPrevio != null && !canalPrevio.equals(canalNuevo)) {
                websub.desuscribir(canalPrevio);
            }
            if (canalNuevo != null && !canalNuevo.isBlank()) {
                if (peticion.estaActivo()) websub.suscribir(canalNuevo);
                else websub.desuscribir(canalNuevo);
            }
            return new Dtos.CreadorGuardado(ref.getId(), null);

        } catch (Exception e) {
            // El creador queda guardado aunque el hub falle; la renovación
            // programada vuelve a intentarlo en el siguiente ciclo.
            log.error("No se pudo sincronizar la suscripción de {}", ref.getId(), e);
            return new Dtos.CreadorGuardado(ref.getId(), e.getMessage());
        }
    }

    @DeleteMapping("/creadores/{id}")
    public Dtos.RespuestaSimple borrar(@PathVariable String id) {
        DocumentReference ref = db.collection(Colecciones.CREADORES).document(id);
        DocumentSnapshot doc = esperar(ref.get());

        if (!doc.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese creador ya no existe.");
        }

        Creador creador = doc.toObject(Creador.class);
        String canal = creador != null ? creador.getCanalDeYouTube() : null;

        if (canal != null && !canal.isBlank()) {
            try {
                websub.desuscribir(canal);
            } catch (Exception e) {
                log.error("Baja del hub fallida para {}", canal, e);
            }
        }

        esperar(ref.delete());
        return Dtos.RespuestaSimple.de("Creador retirado del directorio.");
    }

    /**
     * Busca los datos públicos de un canal para prellenar el formulario.
     * Acepta un ID UC..., un @handle o una URL completa.
     */
    @GetMapping("/canal")
    public Dtos.DatosDeCanal buscarCanal(@RequestParam String query) {
        String entrada = query.trim();
        if (entrada.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escribe un ID de canal, un @handle o una URL.");
        }

        // WebSub exige el ID canónico: los handles no valen como hub.topic.
        String channelId;
        Matcher idDirecto = ID_CANAL.matcher(entrada);

        if (idDirecto.find()) {
            channelId = idDirecto.group(1);
        } else {
            Matcher conArroba = HANDLE.matcher(entrada);
            String handle = conArroba.find()
                    ? conArroba.group(1)
                    : (entrada.startsWith("@") ? entrada.substring(1) : entrada);
            channelId = youtube.resolverHandle(handle);
        }

        if (channelId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró ese canal. Prueba con el ID que empieza por UC.");
        }

        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId);
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El canal existe pero YouTube no devolvió datos.");
        }
        return datos;
    }

    // -------------------------------------------------------------------------
    // Redirección de emergencia
    // -------------------------------------------------------------------------

    /**
     * Cuando YouTube tumba un video por un falso positivo, el destino se
     * reemplaza y la audiencia recibe el enlace nuevo. La entidad del creador
     * nunca se pierde: es lo que evita la caída catastrófica de audiencia
     * cuando alguien es desterrado de una plataforma.
     */
    @PostMapping("/videos/{videoId}/mover")
    public Dtos.RespuestaSimple mover(
            @PathVariable String videoId,
            @Valid @RequestBody Dtos.MoverContenido peticion
    ) {
        DocumentReference videoRef = db.collection(Colecciones.VIDEOS).document(videoId);
        DocumentSnapshot videoDoc = esperar(videoRef.get());

        if (!videoDoc.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese video no está en el directorio.");
        }

        Map<String, Object> cambio = new HashMap<>();
        cambio.put("status", "moved");
        cambio.put("overrideUrl", peticion.url());
        cambio.put("overridePlatform", peticion.plataformaDestino());
        cambio.put("movedAt", Timestamp.now());
        esperar(videoRef.set(cambio, SetOptions.merge()));

        if (peticion.debeAvisar()) {
            String creatorId = videoDoc.getString("creatorId");
            if (creatorId != null) {
                DocumentSnapshot creadorDoc =
                        esperar(db.collection(Colecciones.CREADORES).document(creatorId).get());

                if (creadorDoc.exists()) {
                    Creador creador = creadorDoc.toObject(Creador.class);
                    creador.setId(creadorDoc.getId());
                    try {
                        push.avisarContenidoMovido(
                                creador, videoId, videoDoc.getString("title"),
                                peticion.url(), peticion.plataformaDestino());
                    } catch (Exception e) {
                        log.error("El destino cambió pero la push falló", e);
                        return Dtos.RespuestaSimple.de(
                                "Destino cambiado, pero no se pudo avisar a la audiencia.");
                    }
                }
            }
        }

        return Dtos.RespuestaSimple.de("Destino cambiado.");
    }

    // -------------------------------------------------------------------------
    // Administradores
    // -------------------------------------------------------------------------

    @PostMapping("/administradores")
    public Dtos.RespuestaSimple nombrarAdmin(@RequestParam String correo) {
        UserRecord usuario;
        try {
            usuario = auth.getUserByEmail(correo);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese correo no tiene cuenta todavía. Pide que entre una vez al panel primero.");
        }

        try {
            auth.setCustomUserClaims(usuario.getUid(), Map.of("admin", true));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo asignar el rol.");
        }

        log.info("Rol de administrador otorgado a {}", usuario.getUid());
        return Dtos.RespuestaSimple.de(
                "Listo. Pide a esa persona que cierre sesión y vuelva a entrar.");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> castearMapa(Object valor) {
        return valor instanceof Map ? (Map<String, Object>) valor : null;
    }

    private String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
