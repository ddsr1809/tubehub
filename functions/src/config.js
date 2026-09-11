'use strict';

const admin = require('firebase-admin');
const { defineSecret, defineString } = require('firebase-functions/params');

if (!admin.apps.length) {
  admin.initializeApp();
}

const db = admin.firestore();
db.settings({ ignoreUndefinedProperties: true });

// --- Secretos (se guardan en Secret Manager, nunca en el codigo) --------------
// Se cargan con:  firebase functions:secrets:set NOMBRE
const YOUTUBE_API_KEY = defineSecret('YOUTUBE_API_KEY');
const WEBSUB_SECRET = defineSecret('WEBSUB_SECRET');
const WEBSUB_CALLBACK_TOKEN = defineSecret('WEBSUB_CALLBACK_TOKEN');
const APPLE_PRIVATE_KEY = defineSecret('APPLE_PRIVATE_KEY'); // contenido del .p8

// --- Parametros publicos -----------------------------------------------------
const PUBLIC_BASE_URL = defineString('PUBLIC_BASE_URL', {
  description: 'URL publica de la funcion websub, ej. https://websub-xxxx-uc.a.run.app',
  default: ''
});
const APPLE_TEAM_ID = defineString('APPLE_TEAM_ID', { default: '' });
const APPLE_KEY_ID = defineString('APPLE_KEY_ID', { default: '' });
const APPLE_BUNDLE_ID = defineString('APPLE_BUNDLE_ID', { default: '' });

// --- Constantes del dominio --------------------------------------------------
const HUB_URL = 'https://pubsubhubbub.appspot.com/subscribe';
const YT_FEED = 'https://www.youtube.com/xml/feeds/videos.xml?channel_id=';
const REGION = 'us-central1';

// El hub de Google recorta el lease a ~10 dias. Pedimos el maximo y renovamos
// cada 4 dias, dejando un margen amplio si una renovacion falla.
const LEASE_SECONDS = 864000;

// Ignoramos publicaciones mas viejas que esto para no spamear al suscribirnos
// a un canal por primera vez (el hub reenvia entradas recientes del feed).
const MAX_VIDEO_AGE_MS = 1000 * 60 * 60 * 6; // 6 horas

const PLATFORMS = ['youtube', 'tiktok', 'twitch', 'instagram', 'spotify', 'patreon', 'web'];

const CATEGORIES = ['cine', 'comida', 'politica', 'musica', 'salud', 'noticias', 'tecnologia', 'otros'];

/** Lanza un error si el usuario que llama no tiene el custom claim admin. */
function exigirAdmin(request) {
  const { HttpsError } = require('firebase-functions/v2/https');
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'Inicia sesion para continuar.');
  }
  if (request.auth.token.admin !== true) {
    throw new HttpsError('permission-denied', 'Esta accion es solo para el equipo de moderacion.');
  }
  return request.auth.uid;
}

module.exports = {
  admin,
  db,
  FieldValue: admin.firestore.FieldValue,
  YOUTUBE_API_KEY,
  WEBSUB_SECRET,
  WEBSUB_CALLBACK_TOKEN,
  APPLE_PRIVATE_KEY,
  PUBLIC_BASE_URL,
  APPLE_TEAM_ID,
  APPLE_KEY_ID,
  APPLE_BUNDLE_ID,
  HUB_URL,
  YT_FEED,
  REGION,
  LEASE_SECONDS,
  MAX_VIDEO_AGE_MS,
  PLATFORMS,
  CATEGORIES,
  exigirAdmin
};
