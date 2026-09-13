package com.tuempresa.relay.websub;

import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.PushService;
import com.tuempresa.relay.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class WebSubService {

    private static final Logger log = LoggerFactory.getLogger(WebSubService.class);

    private final Repositorios.Creadores creadores;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Suscripciones suscripciones;
    private final RestClient http;
    private final RelayProperties config;
    private final YouTubeClient youtube;
    private final PushService push;
    private final LectorDeFeed lector;

    public WebSubService(Repositorios.Creadores creadores,
                         Repositorios.Publicaciones publicaciones,
                         Repositorios.Suscripciones suscripciones,
                         RestClient http, RelayProperties config,
                         YouTubeClient youtube, PushService push, LectorDeFeed lector) {
        this.creadores = creadores;
        this.publicaciones = publicaciones;
        this.suscripciones = suscripciones;
        this.http = http;
        this.config = config;
        this.youtube = youtube;
        this.push = push;
        this.lector = lector;
    }

    // -------------------------------------------------------------------------
    // Alta y baja de suscripciones
    // -------------------------------------------------------------------------

    public void suscribir(String channelId) { handshake(channelId, "subscribe"); }

    public void desuscribir(String channelId) { handshake(channelId, "unsubscribe"); }

    /**
     * Manda el handshake al hub, que responde 202 y despues nos llama por GET
     * para verificar la intencion.
     *
     * Dejamos constancia en la base ANTES de llamar, y en su propia
     * transaccion: Google verifica de forma asincrona y su GET puede llegar
     * antes de que esta peticion termine. Si el registro no estuviera
     * confirmado, rechazariamos nuestra propia suscripcion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handshake(String channelId, String modo) {
        if (config.urlPublica().isBlank()) {
            throw new IllegalStateException(
                    "Falta RELAY_URL_PUBLICA. El hub no sabría a dónde entregar los avisos.");
        }

        String topic = RelayProperties.feedDe(channelId);

        Suscripcion registro = suscripciones.findById(channelId).orElseGet(Suscripcion::new);
        registro.setChannelId(channelId);
        registro.setTopic(topic);
        registro.setModo(modo);
        registro.setEstado(Suscripcion.PENDIENTE);
        registro.setSolicitadoEn(Instant.now());
        registro.setUltimoError(null);
        suscripciones.saveAndFlush(registro);

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("hub.mode", modo);
        formulario.add("hub.topic", topic);
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
            registro.setEstado(Suscripcion.ERROR);
            registro.setUltimoError(recortar(e.getMessage(), 500));
            suscripciones.save(registro);
            throw new IllegalStateException("El hub rechazó la petición: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Verificación de intención (GET del hub)
    // -------------------------------------------------------------------------

    /**
     * Devuelve el challenge a responder tal cual, o null si no procede.
     *
     * El hub exige HTTP 200 con el valor exacto de hub.challenge como texto
     * plano. Si respondemos JSON, una redireccion o cualquier otro codigo,
     * descarta la suscripcion en silencio y nunca sabriamos por que dejaron de
     * llegar avisos.
     */
    @Transactional
    public String verificarIntencion(String modo, String topic, String challenge, long lease) {
        String channelId = lector.channelIdDelTopic(topic);
        if (channelId == null) {
            log.warn("Topic no reconocido: {}", topic);
            return null;
        }

        Optional<Suscripcion> encontrada = suscripciones.findById(channelId);
        if (encontrada.isEmpty()) {
            // Sin esta comprobación, cualquiera podría registrar nuestro
            // webhook como destino de feeds ajenos y usarnos de altavoz.
            log.warn("Verificación de un canal que nunca solicitamos: {}", channelId);
            return null;
        }

        Suscripcion registro = encontrada.get();
        registro.setEstado("subscribe".equals(modo) ? Suscripcion.ACTIVA : Suscripcion.CANCELADA);
        registro.setLeaseSegundos(lease > 0 ? lease : null);
        registro.setExpiraEn(lease > 0 ? Instant.now().plusSeconds(lease) : null);
        registro.setVerificadoEn(Instant.now());
        registro.setUltimoError(null);
        suscripciones.save(registro);

        log.info("Suscripción {} verificada para {} (lease {}s)", modo, channelId, lease);
        return challenge;
    }

    // -------------------------------------------------------------------------
    // Llegada de contenido (POST del hub)
    // -------------------------------------------------------------------------

    public void procesarAviso(byte[] cuerpo) {
        LectorDeFeed.Feed feed = lector.leer(cuerpo);

        for (String videoId : feed.borrados()) {
            marcarRetirado(videoId);
        }

        for (LectorDeFeed.Entrada entrada : feed.entradas()) {
            try {
                procesarEntrada(entrada);
            } catch (Exception e) {
                // Un fallo en una entrada no debe tumbar el lote ni provocar
                // reintentos del hub, que acabarían cancelando la suscripción.
                log.error("Fallo procesando la entrada {}", entrada.videoId(), e);
            }
        }
    }

    @Transactional
    public void marcarRetirado(String videoId) {
        publicaciones.findByVideoId(videoId).ifPresent(p -> {
            p.setEstado(Publicacion.RETIRADO);
            publicaciones.save(p);
            log.info("Video {} marcado como retirado", videoId);
        });
    }

    @Transactional
    public void procesarEntrada(LectorDeFeed.Entrada entrada) {
        // 1. Encontrar al creador. Si el canal no está en el directorio curado
        //    o está desactivado, no hay a quién avisar.
        Optional<Creador> encontrado = creadores.porCanalDeYouTube(entrada.channelId());
        if (encontrado.isEmpty()) {
            log.debug("Canal {} fuera del directorio", entrada.channelId());
            return;
        }

        Creador creador = encontrado.get();
        if (!creador.isActivo()) return;

        // 2. Idempotencia. El hub entrega "al menos una vez" y YouTube reenvía
        //    la entrada cada vez que el creador edita el título. El índice
        //    único sobre video_id es quien decide de verdad: si dos hilos
        //    llegan a la vez, uno inserta y el otro recibe la violación.
        if (publicaciones.existsByVideoId(entrada.videoId())) {
            log.debug("Entrada repetida ignorada: {}", entrada.videoId());
            return;
        }

        // 3. Descartar backfill: al suscribirnos, el hub reenvía entradas
        //    recientes del feed y no queremos avisar de videos de la semana
        //    pasada como si acabaran de salir.
        Instant limite = Instant.now().minusSeconds(config.websub().antiguedadMaximaHoras() * 3600);
        boolean esViejo = entrada.publicado() != null && entrada.publicado().isBefore(limite);

        // 4. Enriquecer: 1 unidad de cuota. El XML de WebSub llega sin
        //    descripción ni miniatura, así que sin este paso no se puede armar
        //    una notificación presentable.
        YouTubeClient.DetalleDeVideo detalle = youtube.detallesDeVideo(entrada.videoId());

        Publicacion p = new Publicacion();
        p.setVideoId(entrada.videoId());
        p.setCreadorId(creador.getId());
        p.setPlataforma("youtube");
        p.setTitulo(detalle != null ? detalle.titulo()
                : (entrada.titulo() != null ? entrada.titulo() : "Video nuevo"));
        p.setDescripcion(detalle != null ? detalle.descripcion() : null);
        p.setMiniaturaUrl(detalle != null && detalle.miniatura() != null
                ? detalle.miniatura()
                : "https://i.ytimg.com/vi/" + entrada.videoId() + "/hqdefault.jpg");
        p.setDuracion(detalle != null ? detalle.duracion() : null);
        p.setTipo(detalle != null ? detalle.tipo() : "video");
        p.setEnVivo(detalle != null && detalle.enVivo());
        p.setUrl("https://www.youtube.com/watch?v=" + entrada.videoId());
        p.setPublicadoEn(entrada.publicado() != null ? entrada.publicado() : Instant.now());
        p.setEstado(Publicacion.OK);

        try {
            publicaciones.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            // Otro hilo se nos adelantó con el mismo video. Es exactamente lo
            // que queremos que pase: solo uno manda la push.
            log.debug("Carrera resuelta por el índice único: {}", entrada.videoId());
            return;
        }

        if (esViejo) {
            log.info("Video {} guardado sin notificar (publicado {})",
                    entrada.videoId(), entrada.publicado());
            return;
        }

        // 5. Avisar.
        push.avisarPublicacion(creador, entrada.videoId(), p.getTitulo(),
                p.getMiniaturaUrl(), detalle);

        p.setNotificado(true);
        publicaciones.save(p);
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
        List<Creador> activos = creadores.activosConYouTube();

        int renovados = 0;
        int fallidos = 0;
        List<String> errores = new ArrayList<>();

        for (Creador creador : activos) {
            String canal = creador.getCanalDeYouTube();
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

    private String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
