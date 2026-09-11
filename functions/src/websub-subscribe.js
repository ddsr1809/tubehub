'use strict';

const { logger } = require('firebase-functions');
const {
  db,
  FieldValue,
  HUB_URL,
  YT_FEED,
  LEASE_SECONDS,
  PUBLIC_BASE_URL,
  WEBSUB_SECRET,
  WEBSUB_CALLBACK_TOKEN
} = require('./config');

/**
 * Construye la URL del webhook incluyendo un token opaco.
 * El hub devuelve esta misma URL en la verificacion GET, asi que el token
 * viaja de ida y vuelta y nos deja descartar trafico basura de inmediato.
 */
function urlDeCallback() {
  const base = PUBLIC_BASE_URL.value();
  if (!base) {
    throw new Error(
      'Falta PUBLIC_BASE_URL. Despliega la funcion websub, copia su URL y ponla en functions/.env'
    );
  }
  return `${base.replace(/\/$/, '')}?token=${encodeURIComponent(WEBSUB_CALLBACK_TOKEN.value())}`;
}

/**
 * Manda el handshake al hub. El hub responde 202 y despues nos llama por GET
 * para verificar la intencion; ese paso lo atiende websub-webhook.js.
 *
 * @param {string} channelId  ID canonico del canal (UC...), no el @handle.
 * @param {'subscribe'|'unsubscribe'} modo
 */
async function pedirAlHub(channelId, modo = 'subscribe') {
  // Dejamos constancia ANTES de llamar al hub. Google verifica de forma
  // asincrona y su GET puede llegar antes de que termine este fetch; si el
  // documento no existiera todavia, rechazariamos nuestra propia suscripcion.
  await db.collection('websub').doc(channelId).set(
    {
      channelId,
      topic: YT_FEED + channelId,
      modo,
      estado: 'pendiente_verificacion',
      solicitadoEn: FieldValue.serverTimestamp()
    },
    { merge: true }
  );

  const cuerpo = new URLSearchParams({
    'hub.mode': modo,
    'hub.topic': YT_FEED + channelId,
    'hub.callback': urlDeCallback(),
    'hub.verify': 'async',
    'hub.secret': WEBSUB_SECRET.value(),
    'hub.lease_seconds': String(LEASE_SECONDS)
  });

  const res = await fetch(HUB_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: cuerpo.toString()
  });

  const texto = await res.text().catch(() => '');

  // 202 Accepted es la respuesta normal: el hub aceptó la peticion y verificara
  // despues. 204 tambien aparece en algunas implementaciones.
  const ok = res.status === 202 || res.status === 204;

  await db.collection('websub').doc(channelId).set(
    {
      httpStatus: res.status,
      // Si el hub ya nos verifico durante este fetch, no pisamos el estado
      // 'activa' que escribio el webhook.
      ...(ok ? {} : { estado: 'error' }),
      ultimoError: ok ? null : texto.slice(0, 500)
    },
    { merge: true }
  );

  if (!ok) {
    logger.error('El hub rechazo la peticion', { channelId, modo, status: res.status, texto });
    throw new Error(`Hub respondio ${res.status}: ${texto.slice(0, 200)}`);
  }

  logger.info('Handshake enviado al hub', { channelId, modo });
  return true;
}

const suscribir = (channelId) => pedirAlHub(channelId, 'subscribe');
const desuscribir = (channelId) => pedirAlHub(channelId, 'unsubscribe');

module.exports = { suscribir, desuscribir, urlDeCallback, pedirAlHub };
