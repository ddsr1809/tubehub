'use strict';

const { logger } = require('firebase-functions');
const { db } = require('./config');
const { suscribir } = require('./websub-subscribe');

// El hub de Google recorta el arrendamiento a unos 10 dias como maximo, sin
// importar cuanto pidamos. Pasado ese plazo deja de enviar avisos y no notifica
// nada: la app simplemente se queda muda. Renovamos cada 4 dias para que dos
// fallos seguidos no rompan el servicio.
//
// Esta funcion la dispara Cloud Scheduler a traves de Pub/Sub. Entre ejecuciones
// no hay ninguna instancia encendida, asi que el costo en reposo es cero.

async function renovarSuscripciones() {
  const creadores = await db.collection('creators').where('active', '==', true).get();

  let renovados = 0;
  let fallidos = 0;
  const errores = [];

  for (const doc of creadores.docs) {
    const canal = ((doc.data().platforms || {}).youtube || {}).channelId;
    if (!canal) continue;

    try {
      // Reenviar el handshake es idempotente: si la suscripcion sigue viva,
      // el hub simplemente extiende el plazo.
      await suscribir(canal);
      renovados += 1;
    } catch (err) {
      fallidos += 1;
      errores.push({ canal, error: err.message });
      logger.error('Renovacion fallida', { canal, error: err.message });
    }

    // Ritmo suave para no disparar el limite de peticiones del hub.
    await new Promise((r) => setTimeout(r, 120));
  }

  logger.info('Ciclo de renovacion terminado', { renovados, fallidos });
  return { renovados, fallidos, errores: errores.slice(0, 20) };
}

/**
 * Revisa suscripciones que quedaron en 'pendiente_verificacion' mas de una hora.
 * Suele significar que el webhook no respondio bien al challenge y el hub
 * descarto la suscripcion en silencio.
 */
async function detectarSuscripcionesMudas() {
  const limite = new Date(Date.now() - 60 * 60 * 1000);
  const pendientes = await db.collection('websub')
    .where('estado', '==', 'pendiente_verificacion')
    .get();

  const sospechosas = pendientes.docs
    .filter((d) => {
      const t = d.data().solicitadoEn;
      return t && t.toDate() < limite;
    })
    .map((d) => d.id);

  if (sospechosas.length) {
    logger.warn('Suscripciones sin verificar', { canales: sospechosas });
  }
  return sospechosas;
}

module.exports = { renovarSuscripciones, detectarSuscripcionesMudas };
