'use strict';

const { logger } = require('firebase-functions');
const { admin } = require('./config');

// Usamos topics de FCM en lugar de guardar tokens de dispositivo:
// - un envio alcanza a toda la audiencia de un creador, sin fan-out ni cuota;
// - el cliente se suscribe y desuscribe solo al marcar o quitar un favorito;
// - no almacenamos identificadores de dispositivo, lo que simplifica el borrado
//   de cuenta que exige la Guideline 5.1.1(v) de Apple.

const topicDeCreador = (creatorId) => `creator_${creatorId}`;

/**
 * Notifica una publicacion nueva.
 * El cuerpo esta escrito para que se entienda de un vistazo: quien publico,
 * que publico y nada mas. Sin emojis decorativos ni jerga de plataforma.
 */
async function avisarPublicacion({ creador, video }) {
  const nombre = creador.name;
  const plataforma = etiquetaDePlataforma(video.platform);

  let titulo;
  if (video.esEnVivo) titulo = `${nombre} está en vivo ahora`;
  else if (video.tipo === 'short') titulo = `${nombre} publicó un video corto`;
  else titulo = `${nombre} subió un video nuevo`;

  const mensaje = {
    topic: topicDeCreador(creador.id),
    notification: {
      title: titulo,
      body: video.title || `Toca para verlo en ${plataforma}.`
    },
    // Los datos viajan aparte para que la app resuelva el enlace profundo al
    // abrir la notificacion, incluso si el destino cambio despues del envio.
    data: {
      tipo: 'publicacion',
      creatorId: creador.id,
      videoId: String(video.videoId || ''),
      platform: String(video.platform || 'youtube'),
      url: String(video.url || '')
    },
    android: {
      priority: 'high',
      notification: {
        // El canal lo crea la app Android en RelayApp.onCreate().
        // Separarlo de 'avisos' deja que el usuario silencie las publicaciones
        // sin perder los avisos de contenido movido.
        channelId: 'publicaciones',
        imageUrl: video.thumbnailUrl || undefined,
        // Un solo aviso por creador: si llegan tres videos seguidos, el ultimo
        // reemplaza al anterior en vez de apilar tres tarjetas.
        tag: `creator_${creador.id}`
      }
    },
    apns: {
      headers: { 'apns-priority': '10' },
      payload: {
        aps: {
          sound: 'default',
          'thread-id': `creator_${creador.id}`,
          'mutable-content': 1
        }
      },
      fcmOptions: { imageUrl: video.thumbnailUrl || undefined }
    }
  };

  const id = await admin.messaging().send(mensaje);
  logger.info('Push enviada', { creatorId: creador.id, videoId: video.videoId, messageId: id });
  return id;
}

/**
 * Aviso de contenido movido. Es la pieza que hace resiliente al directorio:
 * si YouTube tumba un video o cierra un canal, el destino se reemplaza y la
 * audiencia recibe el enlace nuevo sin tener que buscar nada.
 */
async function avisarContenidoMovido({ creador, video, destino }) {
  const mensaje = {
    topic: topicDeCreador(creador.id),
    notification: {
      title: `El video de ${creador.name} cambió de lugar`,
      body: video.title
        ? `"${video.title}" ahora está en ${etiquetaDePlataforma(destino.platform)}. Toca para verlo.`
        : `Ahora está en ${etiquetaDePlataforma(destino.platform)}. Toca para verlo.`
    },
    data: {
      tipo: 'movido',
      creatorId: creador.id,
      videoId: String(video.videoId || ''),
      platform: String(destino.platform || 'web'),
      url: String(destino.url || '')
    },
    android: { priority: 'high', notification: { channelId: 'avisos' } },
    apns: { headers: { 'apns-priority': '10' }, payload: { aps: { sound: 'default' } } }
  };

  const id = await admin.messaging().send(mensaje);
  logger.info('Push de contenido movido enviada', { creatorId: creador.id, destino });
  return id;
}

function etiquetaDePlataforma(p) {
  const mapa = {
    youtube: 'YouTube',
    tiktok: 'TikTok',
    twitch: 'Twitch',
    instagram: 'Instagram',
    spotify: 'Spotify',
    patreon: 'Patreon',
    web: 'su página'
  };
  return mapa[p] || 'la plataforma del creador';
}

module.exports = { avisarPublicacion, avisarContenidoMovido, topicDeCreador, etiquetaDePlataforma };
