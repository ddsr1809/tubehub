package com.tuempresa.relay.push;

import com.google.firebase.messaging.*;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Envío de avisos por topics de FCM.
 *
 * Usamos topics en lugar de guardar tokens de dispositivo: un solo envío
 * alcanza a toda la audiencia de un creador, sin fan-out ni cuota, y no
 * almacenamos identificadores de aparato, lo que simplifica el borrado de
 * cuenta que exige la Guideline 5.1.1(v) de Apple.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    // Estos identificadores tienen que coincidir letra por letra con los
    // canales que crea la app de Android en RelayApp.onCreate(). Si no
    // coinciden, el aviso llega pero cae en un canal genérico llamado "Otros".
    public static final String CANAL_PUBLICACIONES = "publicaciones";
    public static final String CANAL_AVISOS = "avisos";

    private final FirebaseMessaging mensajeria;

    public PushService(FirebaseMessaging mensajeria) {
        this.mensajeria = mensajeria;
    }

    public static String topicDe(String creatorId) {
        return "creator_" + creatorId;
    }

    public static String nombreDePlataforma(String plataforma) {
        if (plataforma == null) return "la plataforma del creador";
        return switch (plataforma) {
            case "youtube" -> "YouTube";
            case "tiktok" -> "TikTok";
            case "twitch" -> "Twitch";
            case "instagram" -> "Instagram";
            case "spotify" -> "Spotify";
            case "patreon" -> "Patreon";
            case "web" -> "su página";
            default -> "la plataforma del creador";
        };
    }

    /**
     * Notifica una publicación nueva.
     *
     * El texto está escrito para que se entienda de un vistazo: quién publicó,
     * qué publicó, y nada más. Sin emojis decorativos ni jerga de plataforma.
     */
    public String avisarPublicacion(
            Creador creador,
            String videoId,
            String titulo,
            String miniatura,
            YouTubeClient.DetalleDeVideo detalle
    ) throws FirebaseMessagingException {

        String encabezado;
        if (detalle != null && detalle.esEnVivo()) {
            encabezado = creador.getName() + " está en vivo ahora";
        } else if (detalle != null && "short".equals(detalle.tipo())) {
            encabezado = creador.getName() + " publicó un video corto";
        } else {
            encabezado = creador.getName() + " subió un video nuevo";
        }

        String cuerpo = (titulo == null || titulo.isBlank())
                ? "Toca para verlo en YouTube."
                : titulo;

        // Los datos viajan aparte para que la app resuelva el enlace profundo
        // al abrir la notificación, incluso si el destino cambió después.
        Map<String, String> datos = new HashMap<>();
        datos.put("tipo", "publicacion");
        datos.put("creatorId", creador.getId());
        datos.put("videoId", videoId);
        datos.put("platform", "youtube");
        datos.put("url", "https://www.youtube.com/watch?v=" + videoId);

        AndroidNotification.Builder androidNotif = AndroidNotification.builder()
                .setChannelId(CANAL_PUBLICACIONES)
                // Un solo aviso por creador: si llegan tres videos seguidos, el
                // último reemplaza al anterior en vez de apilar tres tarjetas.
                .setTag("creator_" + creador.getId());

        if (miniatura != null) androidNotif.setImage(miniatura);

        Notification.Builder notificacion = Notification.builder()
                .setTitle(encabezado)
                .setBody(cuerpo);

        if (miniatura != null) notificacion.setImage(miniatura);

        ApnsFcmOptions.Builder apnsOpciones = ApnsFcmOptions.builder();
        if (miniatura != null) apnsOpciones.setImage(miniatura);

        Message mensaje = Message.builder()
                .setTopic(topicDe(creador.getId()))
                .setNotification(notificacion.build())
                .putAllData(datos)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(androidNotif.build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .setAps(Aps.builder()
                                .setSound("default")
                                .setThreadId("creator_" + creador.getId())
                                .setMutableContent(true)
                                .build())
                        .setFcmOptions(apnsOpciones.build())
                        .build())
                .build();

        String id = mensajeria.send(mensaje);
        log.info("Push enviada a los seguidores de {} por el video {}", creador.getName(), videoId);
        return id;
    }

    /**
     * Aviso de contenido movido.
     *
     * Es la pieza que hace resiliente al directorio: si YouTube tumba un video
     * o cierra un canal, el destino se reemplaza y la audiencia recibe el
     * enlace nuevo sin tener que buscar nada. Es la diferencia entre que un
     * creador pierda su audiencia y que solo pierda un video.
     */
    public String avisarContenidoMovido(
            Creador creador,
            String videoId,
            String tituloVideo,
            String destinoUrl,
            String destinoPlataforma
    ) throws FirebaseMessagingException {

        String donde = nombreDePlataforma(destinoPlataforma);
        String cuerpo = (tituloVideo != null && !tituloVideo.isBlank())
                ? "\"" + tituloVideo + "\" ahora está en " + donde + ". Toca para verlo."
                : "Ahora está en " + donde + ". Toca para verlo.";

        Map<String, String> datos = new HashMap<>();
        datos.put("tipo", "movido");
        datos.put("creatorId", creador.getId());
        datos.put("videoId", videoId);
        datos.put("platform", destinoPlataforma);
        datos.put("url", destinoUrl);

        Message mensaje = Message.builder()
                .setTopic(topicDe(creador.getId()))
                .setNotification(Notification.builder()
                        .setTitle("El video de " + creador.getName() + " cambió de lugar")
                        .setBody(cuerpo)
                        .build())
                .putAllData(datos)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId(CANAL_AVISOS)
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();

        String id = mensajeria.send(mensaje);
        log.info("Push de contenido movido enviada para {}", creador.getName());
        return id;
    }
}
