'use strict';

const { onRequest, onCall } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { setGlobalOptions } = require('firebase-functions/v2');

const cfg = require('./src/config');
const webhook = require('./src/websub-webhook');
const adminFns = require('./src/admin');
const cuenta = require('./src/cuenta');
const { renovarSuscripciones, detectarSuscripcionesMudas } = require('./src/renovar');

setGlobalOptions({
  region: cfg.REGION,
  maxInstances: 10,
  memory: '256MiB'
});

// -----------------------------------------------------------------------------
// Webhook de WebSub. Esta es la URL que va en PUBLIC_BASE_URL.
// -----------------------------------------------------------------------------
exports.websub = onRequest(
  {
    secrets: [cfg.WEBSUB_SECRET, cfg.WEBSUB_CALLBACK_TOKEN, cfg.YOUTUBE_API_KEY],
    invoker: 'public', // el hub de Google llama sin credenciales
    concurrency: 40,
    timeoutSeconds: 60
  },
  (req, res) => webhook.manejar(req, res)
);

// -----------------------------------------------------------------------------
// Renovacion de arrendamientos. Cloud Scheduler + Pub/Sub por debajo.
// -----------------------------------------------------------------------------
exports.renovarWebsub = onSchedule(
  {
    schedule: '0 4 */4 * *', // cada 4 dias a las 04:00
    timeZone: 'America/Hermosillo',
    secrets: [cfg.WEBSUB_SECRET, cfg.WEBSUB_CALLBACK_TOKEN],
    timeoutSeconds: 540,
    retryCount: 3
  },
  async () => {
    await renovarSuscripciones();
    await detectarSuscripcionesMudas();
  }
);

// -----------------------------------------------------------------------------
// Mantenimiento diario: purga de cuentas anonimas inactivas.
// -----------------------------------------------------------------------------
exports.purgarAnonimas = onSchedule(
  { schedule: '0 5 * * *', timeZone: 'America/Hermosillo', timeoutSeconds: 540 },
  async () => {
    await cuenta.purgarAnonimasInactivas(30);
  }
);

// -----------------------------------------------------------------------------
// Panel de moderacion
// -----------------------------------------------------------------------------
const secretosAdmin = [cfg.YOUTUBE_API_KEY, cfg.WEBSUB_SECRET, cfg.WEBSUB_CALLBACK_TOKEN];

exports.guardarCreador = onCall({ secrets: secretosAdmin }, adminFns.guardarCreador);
exports.borrarCreador = onCall({ secrets: secretosAdmin }, adminFns.borrarCreador);
exports.buscarCanal = onCall({ secrets: [cfg.YOUTUBE_API_KEY] }, adminFns.buscarCanal);
exports.moverContenido = onCall(adminFns.moverContenido);
exports.darRolAdmin = onCall(adminFns.darRolAdmin);

// -----------------------------------------------------------------------------
// App movil
// -----------------------------------------------------------------------------
exports.reportarEnlace = onCall(adminFns.reportarEnlace);

exports.borrarCuenta = onCall(
  {
    secrets: [cfg.APPLE_PRIVATE_KEY],
    timeoutSeconds: 120
  },
  cuenta.borrarCuenta
);

exports.guardarTokenApple = onCall({ secrets: [cfg.APPLE_PRIVATE_KEY] }, cuenta.guardarTokenApple);
