package com.tuempresa.relay.websub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lectura del Atom que manda YouTube.
 *
 * La carga útil llega mutilada respecto al estándar: sin descripción, sin
 * miniatura y a veces con el título sustituido por un genérico. Por eso aquí
 * solo sacamos los identificadores, y los metadatos reales se piden después a
 * la Data API. Extraer más de este XML sería construir sobre arena.
 */
@Component
public class LectorDeFeed {

    private static final Logger log = LoggerFactory.getLogger(LectorDeFeed.class);
    private static final Pattern CHANNEL_ID = Pattern.compile("channel_id=([\\w-]+)");

    private final XmlMapper xml = new XmlMapper();

    public record Entrada(String videoId, String channelId, String titulo, Instant publicado) {}

    public record Feed(List<Entrada> entradas, List<String> borrados) {}

    public Feed leer(byte[] cuerpo) {
        JsonNode raiz;
        try {
            raiz = xml.readTree(cuerpo);
        } catch (Exception e) {
            log.error("XML de WebSub ilegible", e);
            return new Feed(List.of(), List.of());
        }

        // Aviso de borrado: el creador quitó el video.
        List<String> borrados = new ArrayList<>();
        for (JsonNode nodo : nodos(raiz, "deleted-entry")) {
            String ref = texto(nodo, "ref");
            if (ref != null) {
                String videoId = ref.replace("yt:video:", "");
                if (!videoId.isBlank()) borrados.add(videoId);
            }
        }

        List<Entrada> entradas = new ArrayList<>();
        for (JsonNode nodo : nodos(raiz, "entry")) {
            String videoId = texto(nodo, "videoId");
            if (videoId == null) {
                String id = texto(nodo, "id");
                if (id != null) videoId = id.replace("yt:video:", "");
            }
            String channelId = texto(nodo, "channelId");

            if (videoId == null || videoId.isBlank() || channelId == null || channelId.isBlank()) {
                continue;
            }

            Instant publicado = null;
            String marca = texto(nodo, "published");
            if (marca != null) {
                try {
                    publicado = Instant.parse(marca);
                } catch (Exception ignorado) {
                    // Una fecha ilegible no debe tirar la entrada entera: sin
                    // ella el video se trata como recién publicado, que es el
                    // comportamiento menos dañino.
                }
            }

            entradas.add(new Entrada(videoId, channelId, texto(nodo, "title"), publicado));
        }

        return new Feed(entradas, borrados);
    }

    /** Saca el ID del canal de la URL del tema. */
    public String channelIdDelTopic(String topic) {
        if (topic == null) return null;
        Matcher m = CHANNEL_ID.matcher(topic);
        return m.find() ? m.group(1) : null;
    }

    /** El XML trae un solo elemento o una lista según cuántos haya. */
    private List<JsonNode> nodos(JsonNode raiz, String nombre) {
        JsonNode nodo = raiz.get(nombre);
        if (nodo == null) return List.of();
        if (!nodo.isArray()) return List.of(nodo);

        List<JsonNode> lista = new ArrayList<>();
        nodo.forEach(lista::add);
        return lista;
    }

    private String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor != null && !valor.isNull() ? valor.asText() : null;
    }
}
