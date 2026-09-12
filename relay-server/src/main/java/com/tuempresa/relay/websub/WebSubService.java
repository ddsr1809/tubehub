package com.tuempresa.relay.websub;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Colecciones;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.EstadoSuscripcion;
import com.tuempresa.relay.push.PushService;
import com.tuempresa.relay.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WebSubService {

    private static final Logger log = LoggerFactory.getLogger(WebSubService.class);

    private final Firestore db;
    private final RestClient http;
    private final RelayProperties config;
    private final YouTubeClient youtube;
    private final PushService push;
    private final LectorDeFeed lector;

    public WebSubService(Firestore db, RestClient http, RelayProperties config,
                         YouTubeClient youtube, PushService push, LectorDeFeed lector) {
        this.db = db;
        this.http = http;
        this.config = config;
        this.youtube = youtube;
        this.push = push;
        this.lector = lector;
    }

    // -------------------------------------------------------------------------
    // Alta y baja de suscripciones
    // -------------------------------------------------------------------------

    public void suscribir(String channelId) {
        handshake(channelId, "subscribe");
    }

    public void desuscribir(String channelId) {
        handshake(channelId, "unsubscribe");
    }

    /**
     * Manda el handshake al hub. El hub responde 202 y después nos llama por
     * GET para verificar la intención.
     *
     * Dejamos constancia en Firestore ANTES de llamar: Google verifica de forma
     * asíncrona y su GET puede llegar antes de que esta petición termine. Si el
     * documento no existiera todavía, rechazaríamos nuestra propia suscripción.
     */
    private void handshake(String channelId, String modo) {
        if (config.urlPublica().isBlank()) {
            throw new IllegalStateException(
                    "Falta RELAY_URL_PUBLICA. El hub no sabría a dónde entregar los avisos.");
        }

        DocumentReference doc = db.collection(Colecciones.WEBSUB).document(channelId);

        Map<String, Object> registro = new HashMap<>();
        registro.put("channelId", channelId);
        registro.put("topic", RelayProperties.feedDe(channelId));
        registro.put("modo", modo);
        registro.put("estado", EstadoSuscripcion.PENDIENTE_VERIFICACION.name());
        registro.put("solicitadoEn", Timestamp.now());
        esperar(doc.set(registro, SetOptions.merge()));

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("hub.mode", modo);
        formulario.add("hub.topic", RelayProperties.feedDe(channelId));
        formulario.add("hub.callback", config.urlWebhook());
        formulario.add("hub.verify", "async");
        formulario.add("hub.secret", config.websub().secreto());
        formulario.add("hub.lease_seconds", String.valueOf(config.websub().leaseSegundos()));

        try {
            var respuesta = http.post()
                    .uri(config.websub().hub())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulario)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Handshake {} enviado para {} (HTTP {})",
                    modo, channelId, respuesta.getStatusCode().value());

        } catch (Exception e) {
            Map<String, Object> fallo = new HashMap<>();
            fallo.put("estado", EstadoSuscripcion.ERROR.name());
            fallo.put("ultimoError", recortar(e.getMessage(), 500));
            esperar(doc.set(fallo, SetOptions.merge()));

            throw new IllegalStateException("El hub rechazó la petición: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Verificación de intención (GET del hub)
    // -------------------------------------------------------------------------

    /**
     * Devuelve el challenge que hay que responder tal cual, o null si la
     * verificación no procede.
     *
     * El hub exige HTTP 200 con el valor exacto de hub.challenge como texto
     * plano. Si respondemos JSON, una redirección o cualquier otro código,
     * descarta la suscripción en silencio y nunca sabríamos por qué dejaron de
     * llegar avisos.
     */
    public String verificarIntencion(String modo, String topic, String challenge, long lease) {
        String channelId = lector.channelIdDelTopic(topic);
        if (channelId == null) {
            log.warn("Topic no reconocido: {}", topic);
            return null;
        }

        DocumentReference doc = db.collection(Colecciones.WEBSUB).document(channelId);
        if (!esperar(doc.get()).exists()) {
            // Sin esta comprobación, cualquiera podría registrar nuestro
            // webhook como destino de feeds ajenos y usarnos de altavoz.
            log.warn("Verificación de un canal que nunca solicitamos: {}", channelId);
            return null;
        }

        Map<String, Object> actualizacion = new HashMap<>();
        actualizacion.put("estado", "subscribe".equals(modo)
                ? EstadoSuscripcion.ACTIVA.name()
                : EstadoSuscripcion.CANCELADA.name());
        actualizacion.put("leaseSeconds", lease);
        actualizacion.put("verificadoEn", Timestamp.now());
        actualizacion.put("ultimoError", null);

        if (lease > 0) {
            actualizacion.put("expiraEn",
                    Timestamp.of(Date.from(Instant.now().plusSeconds(lease))));
        }

        esperar(doc.set(actualizacion, SetOptions.merge()));
        log.info("Suscripción {} verificada para {} (lease {}s)", modo, channelId, lease);

        return challenge;
    }

    // -------------------------------------------------------------------------
    // Llegada de contenido (POST del hub)
    // -------------------------------------------------------------------------

    public void procesarAviso(byte[] cuerpo) {
        LectorDeFeed.Feed feed = lector.leer(cuerpo);

        for (String videoId : feed.borrados()) {
            Map<String, Object> marca = new HashMap<>();
            marca.put("status", "removed");
            marca.put("removedAt", Timestamp.now());
            db.collection(Colecciones.VIDEOS).document(videoId).set(marca, SetOptions.merge());
            log.info("Video {} marcado como retirado", videoId);
        }

        for (LectorDeFeed.Entrada entrada : feed.entradas()) {
            try {
                procesarEntrada(entrada);
            } catch (Exception e) {
                // Un fallo en una entrada no debe tumbar el resto del lote ni
                // provocar reintentos del hub, que acabarían cancelando la
                // suscripción entera.
                log.error("Fallo procesando la entrada {}", entrada.videoId(), e);
            }
        }
    }

    private void procesarEntrada(LectorDeFeed.Entrada entrada) throws Exception {
        // 1. Encontrar al creador. Si el canal no está en el directorio curado
        //    o está desactivado, no hay a quién avisar.
        QuerySnapshot resultado = esperar(db.collection(Colecciones.CREADORES)
                .whereEqualTo("platforms.youtube.channelId", entrada.channelId())
                .limit(1)
                .get());

        if (resultado.isEmpty()) {
            log.debug("Canal {} fuera del directorio", entrada.channelId());
            return;
        }

        QueryDocumentSnapshot creadorDoc = resultado.getDocuments().get(0);
        Creador creador = creadorDoc.toObject(Creador.class);
        creador.setId(creadorDoc.getId());

        if (!creador.isActive()) return;

        // 2. Idempotencia. El hub entrega "al menos una vez" y YouTube reenvía
        //    la entrada cada vez que el creador edita el título. Una
        //    transacción que crea el documento decide quién manda la push.
        DocumentReference videoRef = db.collection(Colecciones.VIDEOS).document(entrada.videoId());

        boolean esNuevo = db.runTransaction(tx -> {
            if (tx.get(videoRef).get().exists()) {
                tx.set(videoRef, Map.of("updatedAt", Timestamp.now()), SetOptions.merge());
                return false;
            }
            Map<String, Object> inicial = new HashMap<>();
            inicial.put("videoId", entrada.videoId());
            inicial.put("creatorId", creador.getId());
            inicial.put("platform", "youtube");
            inicial.put("status", "ok");
            inicial.put("notificado", false);
            inicial.put("detectadoEn", Timestamp.now());
            tx.set(videoRef, inicial);
            return true;
        }).get();

        if (!esNuevo) {
            log.debug("Entrada repetida ignorada: {}", entrada.videoId());
            return;
        }

        // 3. Descartar backfill: al suscribirnos, el hub reenvía entradas
        //    recientes del feed y no queremos avisar de videos de la semana
        //    pasada como si acabaran de salir.
        Instant limite = Instant.now().minusSeconds(config.websub().antiguedadMaximaHoras() * 3600);
        boolean esViejo = entrada.publicado() != null && entrada.publicado().isBefore(limite);

        // 4. Enriquecer: 1 unidad de cuota.
        YouTubeClient.DetalleDeVideo detalle = youtube.detallesDeVideo(entrada.videoId());

        String titulo = detalle != null ? detalle.titulo()
                : (entrada.titulo() != null ? entrada.titulo() : "Video nuevo");
        String miniatura = detalle != null && detalle.miniatura() != null
                ? detalle.miniatura()
                : "https://i.ytimg.com/vi/" + entrada.videoId() + "/hqdefault.jpg";
        Timestamp publicado = entrada.publicado() != null
                ? Timestamp.of(Date.from(entrada.publicado()))
                : Timestamp.now();

        Map<String, Object> datos = new HashMap<>();
        datos.put("videoId", entrada.videoId());
        datos.put("creatorId", creador.getId());
        datos.put("creatorName", creador.getName());
        datos.put("platform", "youtube");
        datos.put("title", titulo);
        datos.put("description", detalle != null ? detalle.descripcion() : "");
        datos.put("thumbnailUrl", miniatura);
        datos.put("duration", detalle != null ? detalle.duracion() : null);
        datos.put("tipo", detalle != null ? detalle.tipo() : "video");
        datos.put("esEnVivo", detalle != null && detalle.esEnVivo());
        datos.put("url", "https://www.youtube.com/watch?v=" + entrada.videoId());
        datos.put("publishedAt", publicado);
        datos.put("status", "ok");

        esperar(videoRef.set(datos, SetOptions.merge()));

        Map<String, Object> ultimo = new HashMap<>();
        ultimo.put("lastVideoId", entrada.videoId());
        ultimo.put("lastPublishedAt", publicado);
        creadorDoc.getReference().set(ultimo, SetOptions.merge());

        if (esViejo) {
            log.info("Video {} guardado sin notificar (publicado {})",
                    entrada.videoId(), entrada.publicado());
            return;
        }

        // 5. Avisar.
        push.avisarPublicacion(creador, entrada.videoId(), titulo, miniatura, detalle);

        Map<String, Object> aviso = new HashMap<>();
        aviso.put("notificado", true);
        aviso.put("notificadoEn", Timestamp.now());
        videoRef.set(aviso, SetOptions.merge());
    }

    // -------------------------------------------------------------------------
    // Renovación de arrendamientos
    // -------------------------------------------------------------------------

    /**
     * El hub recorta el arrendamiento a unos 10 días como máximo, sin importar
     * cuánto pidamos. Pasado ese plazo deja de enviar avisos y no notifica
     * nada: la app simplemente se queda muda. Renovamos cada 4 días para que
     * dos fallos seguidos no rompan el servicio.
     */
    public Dtos.ResultadoRenovacion renovarTodas() {
        List<QueryDocumentSnapshot> creadores;
        try {
            creadores = esperar(db.collection(Colecciones.CREADORES)
                    .whereEqualTo("active", true)
                    .get()).getDocuments();
        } catch (Exception e) {
            log.error("No se pudo leer el directorio para renovar", e);
            return new Dtos.ResultadoRenovacion(0, 0, List.of(e.getMessage()));
        }

        int renovados = 0;
        int fallidos = 0;
        List<String> errores = new ArrayList<>();

        for (QueryDocumentSnapshot doc : creadores) {
            String canal = doc.toObject(Creador.class).getCanalDeYouTube();
            if (canal == null || canal.isBlank()) continue;

            try {
                // Reenviar el handshake es idempotente: si la suscripción sigue
                // viva, el hub simplemente extiende el plazo.
                suscribir(canal);
                renovados++;
            } catch (Exception e) {
                fallidos++;
                errores.add(canal + ": " + e.getMessage());
                log.error("Renovación fallida para {}", canal, e);
            }

            // Ritmo suave para no disparar el límite de peticiones del hub.
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Ciclo de renovación terminado: {} renovados, {} fallidos", renovados, fallidos);
        return new Dtos.ResultadoRenovacion(renovados, fallidos,
                errores.subList(0, Math.min(errores.size(), 20)));
    }

    // -------------------------------------------------------------------------

    /** Firestore devuelve ApiFuture; esto evita repetir el try/catch en cada línea. */
    private <T> T esperar(com.google.api.core.ApiFuture<T> futuro) {
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
