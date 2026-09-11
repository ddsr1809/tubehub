'use strict';

const { logger } = require('firebase-functions');
const { YOUTUBE_API_KEY } = require('./config');

const API = 'https://www.googleapis.com/youtube/v3';

// Regla de oro de cuota: nunca usamos search.list (100 unidades por llamada).
// videos.list, channels.list y playlistItems.list cuestan 1 unidad cada una,
// y siempre las llamamos con un ID que ya conocemos.

/**
 * Trae los metadatos completos de un video. Coste: 1 unidad.
 * El payload XML de WebSub llega mutilado (sin descripcion, sin miniatura y a
 * veces con el titulo generico), asi que este paso es obligatorio para armar
 * una notificacion presentable.
 */
async function detallesDeVideo(videoId) {
  const url =
    `${API}/videos?part=snippet,liveStreamingDetails,contentDetails` +
    `&id=${encodeURIComponent(videoId)}&key=${YOUTUBE_API_KEY.value()}`;

  const res = await fetch(url);
  if (!res.ok) {
    const detalle = await res.text().catch(() => '');
    logger.error('videos.list fallo', { videoId, status: res.status, detalle: detalle.slice(0, 300) });
    return null;
  }

  const data = await res.json();
  const item = data.items && data.items[0];
  if (!item) {
    // Pasa cuando el video es privado, se borro en segundos, o es un Short en
    // proceso. No es un error: simplemente no hay nada que notificar todavia.
    logger.warn('videos.list no devolvio resultados', { videoId });
    return null;
  }

  const s = item.snippet || {};
  const live = item.liveStreamingDetails || null;
  const thumbs = s.thumbnails || {};

  return {
    videoId,
    title: s.title || 'Video nuevo',
    description: (s.description || '').slice(0, 1500),
    channelId: s.channelId || null,
    channelTitle: s.channelTitle || null,
    thumbnailUrl:
      (thumbs.maxres || thumbs.standard || thumbs.high || thumbs.medium || thumbs.default || {}).url || null,
    publishedAt: s.publishedAt || null,
    duration: (item.contentDetails || {}).duration || null,
    esEnVivo: Boolean(live && live.actualStartTime && !live.actualEndTime),
    esEstreno: Boolean(live && live.scheduledStartTime && !live.actualStartTime),
    tipo: esCorto(item) ? 'short' : 'video'
  };
}

/** Heuristica barata: un Short dura 3 minutos o menos. */
function esCorto(item) {
  const iso = (item.contentDetails || {}).duration || '';
  const m = iso.match(/^PT(?:(\d+)M)?(?:(\d+)S)?$/);
  if (!m) return false;
  const segundos = (parseInt(m[1] || '0', 10) * 60) + parseInt(m[2] || '0', 10);
  return segundos > 0 && segundos <= 180;
}

/**
 * Datos publicos de un canal para llenar el perfil desde el panel de admin.
 * Coste: 1 unidad.
 */
async function detallesDeCanal(channelId) {
  const url =
    `${API}/channels?part=snippet,contentDetails,statistics` +
    `&id=${encodeURIComponent(channelId)}&key=${YOUTUBE_API_KEY.value()}`;

  const res = await fetch(url);
  if (!res.ok) return null;

  const data = await res.json();
  const item = data.items && data.items[0];
  if (!item) return null;

  const s = item.snippet || {};
  return {
    channelId,
    title: s.title,
    description: (s.description || '').slice(0, 800),
    photoUrl: ((s.thumbnails || {}).high || (s.thumbnails || {}).default || {}).url || null,
    handle: s.customUrl || null,
    uploadsPlaylistId: ((item.contentDetails || {}).relatedPlaylists || {}).uploads || null,
    subscriberCount: (item.statistics || {}).subscriberCount || null
  };
}

/**
 * Resuelve un @handle a su channelId (UC...). Coste: 1 unidad.
 * WebSub exige el ID canonico, los handles no sirven como hub.topic.
 */
async function resolverHandle(handle) {
  const limpio = handle.replace(/^@/, '');
  const url =
    `${API}/channels?part=id,snippet&forHandle=@${encodeURIComponent(limpio)}` +
    `&key=${YOUTUBE_API_KEY.value()}`;

  const res = await fetch(url);
  if (!res.ok) return null;

  const data = await res.json();
  const item = data.items && data.items[0];
  return item ? item.id : null;
}

module.exports = { detallesDeVideo, detallesDeCanal, resolverHandle };
