package com.tuempresa.relay;

import com.tuempresa.relay.websub.LectorDeFeed;
import com.tuempresa.relay.websub.VerificadorDeFirma;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas del camino crítico del webhook.
 *
 * Cubren las dos cosas que, si se rompen, rompen el producto entero:
 *
 *  1. La validación de la firma. Si falla abierta, cualquiera que conozca la
 *     URL puede mandar notificaciones falsas a las personas mayores que usan
 *     la app. Es la única barrera que hay, porque el endpoint es público.
 *  2. El parseo del Atom de YouTube, que llega con prefijos de namespace y con
 *     campos ausentes respecto al estándar.
 *
 * No levantan el contexto de Spring ni tocan Firebase: corren en milisegundos.
 */
class WebSubTest {

    private static final String SECRETO = "secreto-de-prueba-no-usar-en-produccion";

    private final VerificadorDeFirma verificador = new VerificadorDeFirma();
    private final LectorDeFeed lector = new LectorDeFeed();

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <title>YouTube video feed</title>
              <entry>
                <id>yt:video:AbC123dEfGh</id>
                <yt:videoId>AbC123dEfGh</yt:videoId>
                <yt:channelId>UCabcdefghijklmnopqrstuv</yt:channelId>
                <title>Cómo hacer pan de muerto en casa</title>
                <author><name>Juan Pérez</name></author>
                <published>2026-09-09T15:04:05Z</published>
                <updated>2026-09-09T15:04:05Z</updated>
              </entry>
            </feed>
            """;

    private static final String BORRADO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:at="http://purl.org/atompub/tombstones/1.0" xmlns="http://www.w3.org/2005/Atom">
              <at:deleted-entry ref="yt:video:AbC123dEfGh" when="2026-09-09T18:00:00Z"/>
            </feed>
            """;

    private String firmar(byte[] cuerpo, String clave) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(clave.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return "sha1=" + HexFormat.of().formatHex(mac.doFinal(cuerpo));
    }

    // --- Firma ---------------------------------------------------------------

    @Test
    @DisplayName("acepta una firma legítima")
    void aceptaFirmaLegitima() throws Exception {
        byte[] cuerpo = FEED.getBytes(StandardCharsets.UTF_8);
        assertTrue(verificador.esValida(cuerpo, firmar(cuerpo, SECRETO), SECRETO));
    }

    @Test
    @DisplayName("rechaza una firma calculada con otro secreto")
    void rechazaOtroSecreto() throws Exception {
        byte[] cuerpo = FEED.getBytes(StandardCharsets.UTF_8);
        assertFalse(verificador.esValida(cuerpo, firmar(cuerpo, "otro-secreto"), SECRETO));
    }

    @Test
    @DisplayName("rechaza un cuerpo alterado con la firma original")
    void rechazaCuerpoAlterado() throws Exception {
        byte[] original = FEED.getBytes(StandardCharsets.UTF_8);
        String firma = firmar(original, SECRETO);

        byte[] manipulado = FEED.replace("Juan Pérez", "Atacante")
                .getBytes(StandardCharsets.UTF_8);

        assertFalse(verificador.esValida(manipulado, firma, SECRETO));
    }

    @Test
    @DisplayName("rechaza cuando no viene cabecera de firma")
    void rechazaSinCabecera() {
        byte[] cuerpo = FEED.getBytes(StandardCharsets.UTF_8);
        assertFalse(verificador.esValida(cuerpo, null, SECRETO));
        assertFalse(verificador.esValida(cuerpo, "", SECRETO));
    }

    @Test
    @DisplayName("rechaza algoritmos no permitidos")
    void rechazaAlgoritmoDesconocido() {
        byte[] cuerpo = FEED.getBytes(StandardCharsets.UTF_8);
        assertFalse(verificador.esValida(cuerpo, "md5=" + "a".repeat(32), SECRETO));
    }

    @Test
    @DisplayName("rechaza una firma de longitud distinta sin reventar")
    void rechazaFirmaCorta() {
        byte[] cuerpo = FEED.getBytes(StandardCharsets.UTF_8);
        assertFalse(verificador.esValida(cuerpo, "sha1=abc", SECRETO));
    }

    @Test
    @DisplayName("rechaza todo si el secreto no está configurado")
    void rechazaSinSecreto() throws Exception {
        byte[] cuerpo = FEED.getBytes(StandardCharsets.UTF_8);
        assertFalse(verificador.esValida(cuerpo, firmar(cuerpo, SECRETO), ""));
    }

    // --- Parseo --------------------------------------------------------------

    @Test
    @DisplayName("extrae videoId y channelId pese al prefijo yt:")
    void extraeIdentificadores() {
        LectorDeFeed.Feed feed = lector.leer(FEED.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, feed.entradas().size());
        LectorDeFeed.Entrada entrada = feed.entradas().get(0);

        assertEquals("AbC123dEfGh", entrada.videoId());
        assertEquals("UCabcdefghijklmnopqrstuv", entrada.channelId());
        assertEquals("Cómo hacer pan de muerto en casa", entrada.titulo());
        assertNotNull(entrada.publicado());
    }

    @Test
    @DisplayName("reconoce un aviso de borrado")
    void reconoceBorrado() {
        LectorDeFeed.Feed feed = lector.leer(BORRADO.getBytes(StandardCharsets.UTF_8));

        assertTrue(feed.entradas().isEmpty());
        assertEquals(List.of("AbC123dEfGh"), feed.borrados());
    }

    @Test
    @DisplayName("saca el channelId del hub.topic")
    void sacaChannelIdDelTopic() {
        String topic = "https://www.youtube.com/xml/feeds/videos.xml?channel_id=UCabcdefghijklmnopqrstuv";

        assertEquals("UCabcdefghijklmnopqrstuv", lector.channelIdDelTopic(topic));
        assertNull(lector.channelIdDelTopic("https://ejemplo.com/otro-feed"));
        assertNull(lector.channelIdDelTopic(null));
    }

    @Test
    @DisplayName("tolera un feed sin entradas")
    void toleraFeedVacio() {
        String vacio = "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<title>YouTube video feed</title></feed>";

        LectorDeFeed.Feed feed = lector.leer(vacio.getBytes(StandardCharsets.UTF_8));
        assertTrue(feed.entradas().isEmpty());
        assertTrue(feed.borrados().isEmpty());
    }

    @Test
    @DisplayName("tolera XML corrupto sin lanzar excepción")
    void toleraXmlCorrupto() {
        LectorDeFeed.Feed feed = lector.leer("esto no es xml <<<".getBytes(StandardCharsets.UTF_8));
        assertTrue(feed.entradas().isEmpty());
    }
}
