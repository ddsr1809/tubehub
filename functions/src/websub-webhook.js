'use strict';

const crypto = require('crypto');
const { logger } = require('firebase-functions');
const { XMLParser } = require('fast-xml-parser');

const {
  db,
  FieldValue,
  WEBSUB_SECRET,
  WEBSUB_CALLBACK_TOKEN,
  MAX_VIDEO_AGE_MS
} = require('./config');
const { detallesDeVideo } = require('./youtube');
const { avisarPublicacion } = require('./push');

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: '@',
  removeNSPrefix: true // 'yt:videoId' llega como 'videoId'
});

// -----------------------------------------------------------------------------
// GET: verificacion de intencion
// -----------------------------------------------------------------------------
// El hub nos llama antes de activar nada. Hay que responder 200 con el valor
// exacto de hub.challenge como texto plano. Si respondemos JSON, una redireccion
// o cualquier otro codigo, el hub descarta la suscripcion en silencio.
async function verificarIntencion(req, res) {
  const modo = req.query['hub.mode'];
  const topic = req.query['hub.topic'];
  const challenge = req.query['hub.challenge'];
  const lease = parseInt(req.query['hub.lease_seconds'] || '0', 10);

  if (!modo || !topic || !challenge) {
    return res.status(400).send('Faltan parametros de verificacion');
  }

  const channelId = channelIdDelTopic(topic);
  if (!channelId) {
    logger.warn('Topic no reconocido', { topic });
    return res.status(404).send('Topic desconocido');
  }

  // Solo confirmamos temas que nosotros pedimos. Sin esto, cualquiera podria
  // registrar nuestro webhook como destino de feeds ajenos.
  const pendiente = await db.collection('websub').doc(channelId).get();
  if (!pendiente.exists) {
    logger.warn('Verificacion de un canal que nunca solicitamos', { channelId, modo });
    return res.status(404).send('No solicitado');
  }

  const expira = lease > 0 ? new Date(Date.now() + lease * 1000) : null;
  await pendiente.ref.set(
    {
      estado: modo === 'subscribe' ? 'activa' : 'cancelada',
      leaseSeconds: lease || null,
      expiraEn: expira,
      verificadoEn: FieldValue.serverTimestamp(),
      ultimoError: null
    },
    { merge: true }
  );

  logger.info('Suscripcion verificada', { channelId, modo, lease });

  res.set('Content-Type', 'text/plain');
  return res.status(200).send(challenge);
}

// -----------------------------------------------------------------------------
// POST: llegada de contenido
// -----------------------------------------------------------------------------
async function recibirPublicacion(req, res) {
  // 1. Autenticar la carga util antes de mirarla siquiera.
  if (!firmaValida(req)) {
    logger.warn('Firma X-Hub-Signature invalida; carga descartada');
    // 200 a proposito: si respondemos error, el hub reintenta y termina
    // cancelando la suscripcion. Descartamos en silencio.
    return res.status(200).send('ok');
  }

  // Terminamos el trabajo ANTES de responder. En Cloud Run la CPU se corta al
  // cerrar la respuesta, asi que cualquier cosa lanzada "en segundo plano"
  // puede quedar a medias. El enriquecimiento tarda pocos cientos de ms y el
  // hub tolera esa espera sin problema.
  let feed;
  try {
    feed = parser.parse(req.rawBody.toString('utf8'));
  } catch (err) {
    logger.error('XML de WebSub ilegible', err);
    return res.status(204).send();
  }

  const raiz = feed.feed || {};

  // Aviso de borrado: el creador quito el video. Lo marcamos, no notificamos.
  if (raiz['deleted-entry']) {
    const ref = String(raiz['deleted-entry']['@ref'] || '');
    const videoId = ref.replace('yt:video:', '');
    if (videoId) {
      await db.collection('videos').doc(videoId)
        .set({ status: 'removed', removedAt: FieldValue.serverTimestamp() }, { merge: true })
        .catch(() => {});
      logger.info('Video marcado como retirado', { videoId });
    }
    return res.status(204).send();
  }

  const entradas = [].concat(raiz.entry || []);
  for (const entrada of entradas) {
    try {
      await procesarEntrada(entrada);
    } catch (err) {
      // Un fallo en una entrada no debe tumbar el resto del lote ni provocar
      // que el hub reintente y acabe cancelando la suscripcion.
      logger.error('Fallo procesando una entrada del feed', err);
    }
  }

  return res.status(204).send();
}

async function procesarEntrada(entrada) {
  const videoId = entrada.videoId || String(entrada.id || '').replace('yt:video:', '');
  const channelId = entrada.channelId;
  if (!videoId || !channelId) return;

  // 2. Encontrar al creador. Si el canal no esta en el directorio curado o esta
  //    desactivado, no hay a quien avisar.
  const snap = await db.collection('creators')
    .where('platforms.youtube.channelId', '==', channelId)
    .limit(1)
    .get();

  if (snap.empty) {
    logger.info('Canal fuera del directorio', { channelId });
    return;
  }

  const creadorDoc = snap.docs[0];
  const creador = { id: creadorDoc.id, ...creadorDoc.data() };
  if (creador.active === false) return;

  // 3. Idempotencia. Pub/Sub y el propio hub entregan "al menos una vez", y
  //    YouTube reenvia la entrada cada vez que el creador edita el titulo.
  //    Una transaccion que crea el documento decide quien manda la push.
  const videoRef = db.collection('videos').doc(videoId);
  const esNuevo = await db.runTransaction(async (tx) => {
    const actual = await tx.get(videoRef);
    if (actual.exists) {
      tx.set(videoRef, { updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      return false;
    }
    tx.set(videoRef, {
      videoId,
      creatorId: creador.id,
      platform: 'youtube',
      status: 'ok',
      notificado: false,
      detectadoEn: FieldValue.serverTimestamp()
    });
    return true;
  });

  if (!esNuevo) {
    logger.info('Entrada repetida ignorada', { videoId });
    return;
  }

  // 4. Descartar backfill. Al suscribirnos, el hub puede mandar entradas viejas.
  const publicado = entrada.published ? new Date(entrada.published) : null;
  const viejo = publicado && Date.now() - publicado.getTime() > MAX_VIDEO_AGE_MS;

  // 5. Enriquecer. El XML de WebSub llega sin descripcion ni miniatura, asi que
  //    pedimos los metadatos reales: 1 unidad de cuota.
  const detalle = await detallesDeVideo(videoId);
  const url = `https://www.youtube.com/watch?v=${videoId}`;

  const datos = {
    videoId,
    creatorId: creador.id,
    creatorName: creador.name,
    platform: 'youtube',
    title: (detalle && detalle.title) || entrada.title || 'Video nuevo',
    description: (detalle && detalle.description) || '',
    thumbnailUrl: (detalle && detalle.thumbnailUrl) || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    duration: detalle ? detalle.duration : null,
    tipo: detalle ? detalle.tipo : 'video',
    esEnVivo: detalle ? detalle.esEnVivo : false,
    url,
    publishedAt: publicado || FieldValue.serverTimestamp(),
    status: 'ok'
  };

  await videoRef.set(datos, { merge: true });
  await creadorDoc.ref.set(
    { lastVideoId: videoId, lastPublishedAt: publicado || FieldValue.serverTimestamp() },
    { merge: true }
  );

  if (viejo) {
    logger.info('Video antiguo guardado sin notificar', { videoId, publicado });
    return;
  }

  // 6. Avisar.
  await avisarPublicacion({ creador, video: { ...datos, ...(detalle || {}) } });
  await videoRef.set({ notificado: true, notificadoEn: FieldValue.serverTimestamp() }, { merge: true });
}

// -----------------------------------------------------------------------------
// Utilidades
// -----------------------------------------------------------------------------

/**
 * Compara la firma del hub con la nuestra en tiempo constante.
 * El hub firma el cuerpo crudo con hub.secret; sin esta comprobacion cualquiera
 * podria inyectar avisos falsos a los usuarios de la app.
 */
function firmaValida(req) {
  const cabecera = req.get('x-hub-signature') || req.get('X-Hub-Signature');
  if (!cabecera || !req.rawBody) return false;

  const [algoritmo, firmaRecibida] = cabecera.split('=');
  if (!algoritmo || !firmaRecibida) return false;
  if (!['sha1', 'sha256', 'sha384', 'sha512'].includes(algoritmo)) return false;

  const propia = crypto
    .createHmac(algoritmo, WEBSUB_SECRET.value())
    .update(req.rawBody)
    .digest('hex');

  const a = Buffer.from(propia, 'utf8');
  const b = Buffer.from(firmaRecibida.trim(), 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function channelIdDelTopic(topic) {
  const m = String(topic).match(/channel_id=([\w-]+)/);
  return m ? m[1] : null;
}

/** Punto de entrada unico que enruta GET y POST. */
async function manejar(req, res) {
  // El token opaco del callback filtra escaneos automatizados antes de gastar
  // cualquier lectura de Firestore.
  const token = req.query.token;
  if (token !== WEBSUB_CALLBACK_TOKEN.value()) {
    return res.status(404).send('No encontrado');
  }

  if (req.method === 'GET') return verificarIntencion(req, res);
  if (req.method === 'POST') return recibirPublicacion(req, res);
  return res.status(405).send('Metodo no permitido');
}

module.exports = { manejar, firmaValida, channelIdDelTopic };
