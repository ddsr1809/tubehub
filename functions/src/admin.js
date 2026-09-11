'use strict';

const { HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { admin, db, FieldValue, PLATFORMS, CATEGORIES, exigirAdmin } = require('./config');
const { suscribir, desuscribir } = require('./websub-subscribe');
const { detallesDeCanal, resolverHandle } = require('./youtube');
const { avisarContenidoMovido } = require('./push');

// -----------------------------------------------------------------------------
// Alta y edicion de creadores
// -----------------------------------------------------------------------------
async function guardarCreador(request) {
  exigirAdmin(request);
  const { id, name, category, bio, photoUrl, platforms = {}, active = true } = request.data || {};

  if (!name || String(name).trim().length < 2) {
    throw new HttpsError('invalid-argument', 'El creador necesita un nombre.');
  }
  if (category && !CATEGORIES.includes(category)) {
    throw new HttpsError('invalid-argument', `Categoria no valida: ${category}`);
  }

  const conexiones = {};
  for (const [plataforma, valor] of Object.entries(platforms)) {
    if (!PLATFORMS.includes(plataforma)) continue;
    if (!valor || !valor.url) continue;
    conexiones[plataforma] = {
      url: String(valor.url).trim(),
      handle: valor.handle ? String(valor.handle).trim() : null,
      channelId: valor.channelId ? String(valor.channelId).trim() : null,
      etiqueta: valor.etiqueta || null
    };
  }

  const ref = id ? db.collection('creators').doc(id) : db.collection('creators').doc();
  const previo = id ? (await ref.get()).data() : null;

  await ref.set(
    {
      name: String(name).trim(),
      category: category || 'otros',
      bio: bio ? String(bio).slice(0, 600) : null,
      photoUrl: photoUrl || null,
      platforms: conexiones,
      active: Boolean(active),
      updatedAt: FieldValue.serverTimestamp(),
      createdAt: previo ? previo.createdAt : FieldValue.serverTimestamp()
    },
    { merge: true }
  );

  // Sincronizar WebSub si el canal de YouTube cambio o se activo/desactivo.
  const canalNuevo = conexiones.youtube ? conexiones.youtube.channelId : null;
  const canalPrevio = previo && previo.platforms && previo.platforms.youtube
    ? previo.platforms.youtube.channelId
    : null;

  try {
    if (canalPrevio && canalPrevio !== canalNuevo) await desuscribir(canalPrevio);
    if (canalNuevo && active) await suscribir(canalNuevo);
    if (canalNuevo && !active) await desuscribir(canalNuevo);
  } catch (err) {
    // El creador queda guardado aunque el hub falle; la renovacion programada
    // vuelve a intentarlo en el siguiente ciclo.
    logger.error('No se pudo sincronizar la suscripcion', err);
    return { id: ref.id, avisoSuscripcion: err.message };
  }

  return { id: ref.id };
}

async function borrarCreador(request) {
  exigirAdmin(request);
  const { id } = request.data || {};
  if (!id) throw new HttpsError('invalid-argument', 'Falta el id del creador.');

  const doc = await db.collection('creators').doc(id).get();
  if (!doc.exists) throw new HttpsError('not-found', 'Ese creador ya no existe.');

  const canal = ((doc.data().platforms || {}).youtube || {}).channelId;
  if (canal) await desuscribir(canal).catch((e) => logger.error('Baja fallida', e));

  await doc.ref.delete();
  return { ok: true };
}

/** Busca los datos publicos de un canal para prellenar el formulario. */
async function buscarCanal(request) {
  exigirAdmin(request);
  const entrada = String((request.data || {}).query || '').trim();
  if (!entrada) throw new HttpsError('invalid-argument', 'Escribe un ID de canal, @handle o URL.');

  let channelId = null;
  const porId = entrada.match(/(UC[\w-]{22})/);
  if (porId) {
    channelId = porId[1];
  } else {
    const handle = (entrada.match(/@([\w.-]+)/) || [])[1] || entrada.replace(/^@/, '');
    channelId = await resolverHandle(handle);
  }

  if (!channelId) {
    throw new HttpsError('not-found', 'No se encontró ese canal. Prueba con el ID que empieza por UC.');
  }

  const datos = await detallesDeCanal(channelId);
  if (!datos) throw new HttpsError('not-found', 'El canal existe pero YouTube no devolvió datos.');
  return datos;
}

// -----------------------------------------------------------------------------
// Redireccion de emergencia
// -----------------------------------------------------------------------------
// Cuando YouTube tumba un video por un falso positivo, el destino se reemplaza
// y la audiencia recibe el enlace nuevo. La entidad del creador nunca se pierde.
async function moverContenido(request) {
  exigirAdmin(request);
  const { videoId, url, platform = 'web', avisar = true } = request.data || {};
  if (!videoId || !url) throw new HttpsError('invalid-argument', 'Falta el video o el enlace nuevo.');
  if (!/^https?:\/\//i.test(url)) throw new HttpsError('invalid-argument', 'El enlace debe empezar por https.');

  const videoRef = db.collection('videos').doc(videoId);
  const videoDoc = await videoRef.get();
  if (!videoDoc.exists) throw new HttpsError('not-found', 'Ese video no está en el directorio.');

  const video = videoDoc.data();
  await videoRef.set(
    {
      status: 'moved',
      overrideUrl: url,
      overridePlatform: platform,
      movedAt: FieldValue.serverTimestamp()
    },
    { merge: true }
  );

  if (avisar) {
    const creadorDoc = await db.collection('creators').doc(video.creatorId).get();
    if (creadorDoc.exists) {
      await avisarContenidoMovido({
        creador: { id: creadorDoc.id, ...creadorDoc.data() },
        video,
        destino: { url, platform }
      });
    }
  }

  return { ok: true };
}

// -----------------------------------------------------------------------------
// Reportes de enlaces rotos (los manda cualquier usuario desde la app)
// -----------------------------------------------------------------------------
async function reportarEnlace(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Abre la app para poder reportar.');

  const { videoId, creatorId, reason } = request.data || {};
  if (!videoId && !creatorId) throw new HttpsError('invalid-argument', 'Falta el contenido reportado.');

  await db.collection('reports').add({
    uid: request.auth.uid,
    videoId: videoId || null,
    creatorId: creatorId || null,
    reason: String(reason || 'enlace_roto').slice(0, 500),
    resolved: false,
    createdAt: FieldValue.serverTimestamp()
  });

  // Contador rapido para que el panel muestre los casos calientes primero.
  if (videoId) {
    await db.collection('videos').doc(videoId)
      .set({ reportes: FieldValue.increment(1) }, { merge: true })
      .catch(() => {});
  }

  return { ok: true };
}

// -----------------------------------------------------------------------------
// Alta del primer administrador
// -----------------------------------------------------------------------------
// Se ejecuta una sola vez desde la terminal con el script scripts/set-admin.js.
// Deja de funcionar en cuanto exista al menos un admin, para que nadie pueda
// escalar privilegios llamando a la funcion desde fuera.
async function darRolAdmin(request) {
  const { email } = request.data || {};
  if (!email) throw new HttpsError('invalid-argument', 'Falta el correo.');

  const marcador = db.collection('system').doc('bootstrap');
  const yaHayAdmin = (await marcador.get()).exists;

  if (yaHayAdmin) {
    exigirAdmin(request); // a partir de aqui, solo un admin nombra a otro admin
  }

  const usuario = await admin.auth().getUserByEmail(email).catch(() => null);
  if (!usuario) throw new HttpsError('not-found', 'Ese correo no tiene cuenta todavía.');

  await admin.auth().setCustomUserClaims(usuario.uid, { admin: true });
  await marcador.set({ creado: FieldValue.serverTimestamp() }, { merge: true });

  logger.info('Rol de administrador otorgado', { uid: usuario.uid });
  return { ok: true, uid: usuario.uid };
}

module.exports = {
  guardarCreador,
  borrarCreador,
  buscarCanal,
  moverContenido,
  reportarEnlace,
  darRolAdmin
};
