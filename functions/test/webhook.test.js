'use strict';

// Prueba del camino critico del webhook, sin necesidad de Firebase.
//   cd functions && npm install && node test/webhook.test.js
//
// Cubre las dos cosas que, si se rompen, rompen el producto entero:
//   1. La validacion de la firma. Si falla abierta, cualquiera puede mandar
//      notificaciones falsas a las personas mayores que usan la app.
//   2. El parseo del Atom de YouTube, que llega con prefijos de namespace y
//      con campos ausentes.

const crypto = require('crypto');
const assert = require('assert');
const { XMLParser } = require('fast-xml-parser');

const SECRETO = 'secreto-de-prueba-no-usar-en-produccion';

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: '@',
  removeNSPrefix: true
});

// --- Copia de la logica de firma de websub-webhook.js -----------------------
function firmaValida(cuerpoCrudo, cabecera, secreto) {
  if (!cabecera || !cuerpoCrudo) return false;
  const [algoritmo, firmaRecibida] = String(cabecera).split('=');
  if (!algoritmo || !firmaRecibida) return false;
  if (!['sha1', 'sha256', 'sha384', 'sha512'].includes(algoritmo)) return false;

  const propia = crypto.createHmac(algoritmo, secreto).update(cuerpoCrudo).digest('hex');
  const a = Buffer.from(propia, 'utf8');
  const b = Buffer.from(firmaRecibida.trim(), 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function channelIdDelTopic(topic) {
  const m = String(topic).match(/channel_id=([\w-]+)/);
  return m ? m[1] : null;
}

// --- Carga util tal como la manda YouTube ----------------------------------
const FEED = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
  <link rel="hub" href="https://pubsubhubbub.appspot.com"/>
  <title>YouTube video feed</title>
  <entry>
    <id>yt:video:AbC123dEfGh</id>
    <yt:videoId>AbC123dEfGh</yt:videoId>
    <yt:channelId>UCabcdefghijklmnopqrstuv</yt:channelId>
    <title>Cómo hacer pan de muerto en casa</title>
    <link rel="alternate" href="https://www.youtube.com/watch?v=AbC123dEfGh"/>
    <author><name>Juan Pérez</name></author>
    <published>2026-09-09T15:04:05+00:00</published>
    <updated>2026-09-09T15:04:05+00:00</updated>
  </entry>
</feed>`;

const BORRADO = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:at="http://purl.org/atompub/tombstones/1.0" xmlns="http://www.w3.org/2005/Atom">
  <at:deleted-entry ref="yt:video:AbC123dEfGh" when="2026-09-09T18:00:00+00:00"/>
</feed>`;

const cuerpo = Buffer.from(FEED, 'utf8');
const firma = 'sha1=' + crypto.createHmac('sha1', SECRETO).update(cuerpo).digest('hex');

const pruebas = [];
const probar = (nombre, fn) => pruebas.push([nombre, fn]);

// --- Firma ------------------------------------------------------------------
probar('acepta una firma legítima', () => {
  assert.strictEqual(firmaValida(cuerpo, firma, SECRETO), true);
});

probar('rechaza una firma calculada con otro secreto', () => {
  const falsa = 'sha1=' + crypto.createHmac('sha1', 'otro-secreto').update(cuerpo).digest('hex');
  assert.strictEqual(firmaValida(cuerpo, falsa, SECRETO), false);
});

probar('rechaza un cuerpo alterado con la firma original', () => {
  const manipulado = Buffer.from(FEED.replace('Juan Pérez', 'Atacante'), 'utf8');
  assert.strictEqual(firmaValida(manipulado, firma, SECRETO), false);
});

probar('rechaza cuando no viene cabecera de firma', () => {
  assert.strictEqual(firmaValida(cuerpo, undefined, SECRETO), false);
  assert.strictEqual(firmaValida(cuerpo, '', SECRETO), false);
});

probar('rechaza algoritmos no permitidos', () => {
  assert.strictEqual(firmaValida(cuerpo, 'md5=' + 'a'.repeat(32), SECRETO), false);
});

probar('rechaza una firma de longitud distinta sin reventar', () => {
  assert.strictEqual(firmaValida(cuerpo, 'sha1=abc', SECRETO), false);
});

// --- Parseo -----------------------------------------------------------------
probar('extrae videoId y channelId pese al prefijo yt:', () => {
  const entrada = parser.parse(FEED).feed.entry;
  assert.strictEqual(entrada.videoId, 'AbC123dEfGh');
  assert.strictEqual(entrada.channelId, 'UCabcdefghijklmnopqrstuv');
  assert.strictEqual(entrada.title, 'Cómo hacer pan de muerto en casa');
});

probar('reconoce un aviso de borrado', () => {
  const raiz = parser.parse(BORRADO).feed;
  assert.ok(raiz['deleted-entry']);
  const ref = String(raiz['deleted-entry']['@ref']).replace('yt:video:', '');
  assert.strictEqual(ref, 'AbC123dEfGh');
});

probar('saca el channelId del hub.topic', () => {
  const topic = 'https://www.youtube.com/xml/feeds/videos.xml?channel_id=UCabcdefghijklmnopqrstuv';
  assert.strictEqual(channelIdDelTopic(topic), 'UCabcdefghijklmnopqrstuv');
  assert.strictEqual(channelIdDelTopic('https://ejemplo.com/otro-feed'), null);
});

probar('tolera un feed sin entradas', () => {
  const vacio = '<feed xmlns="http://www.w3.org/2005/Atom"><title>YouTube video feed</title></feed>';
  const entradas = [].concat(parser.parse(vacio).feed.entry || []);
  assert.strictEqual(entradas.length, 0);
});

// --- Ejecución --------------------------------------------------------------
let fallos = 0;
for (const [nombre, fn] of pruebas) {
  try {
    fn();
    console.log('  ok   ' + nombre);
  } catch (err) {
    fallos += 1;
    console.log('  FALLA ' + nombre + '\n        ' + err.message);
  }
}

console.log(`\n${pruebas.length - fallos}/${pruebas.length} pruebas pasaron.`);
process.exit(fallos ? 1 : 0);
