// Panel de curaduría. Firebase por CDN, sin bundler: se sirve tal cual con
// `firebase deploy --only hosting` o `firebase emulators:start`.

import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.13.0/firebase-app.js';
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signOut, onAuthStateChanged
} from 'https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js';
import {
  getFirestore, collection, query, orderBy, limit, onSnapshot, where
} from 'https://www.gstatic.com/firebasejs/10.13.0/firebase-firestore.js';
import { firebaseConfig, API_BASE } from './firebase-config.js';

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

// Cliente del servidor Spring Boot.
//
// Cada peticion lleva el ID token de Firebase, que el servidor verifica contra
// Google y traduce a ROLE_ADMIN si el claim `admin` esta presente. El token
// caduca cada hora, asi que lo pedimos en cada llamada: el SDK lo refresca solo
// y lo devuelve desde cache, asi que no cuesta nada.
async function api(ruta, { metodo = 'GET', cuerpo = null } = {}) {
  const usuario = auth.currentUser;
  if (!usuario) throw new Error('Sesion no valida. Vuelve a entrar.');

  const respuesta = await fetch(API_BASE + ruta, {
    method: metodo,
    headers: {
      Authorization: `Bearer ${await usuario.getIdToken()}`,
      ...(cuerpo ? { 'Content-Type': 'application/json' } : {})
    },
    body: cuerpo ? JSON.stringify(cuerpo) : undefined
  });

  const texto = await respuesta.text();
  const datos = texto ? JSON.parse(texto) : {};

  if (!respuesta.ok) {
    // Spring manda el motivo en `message`. Mostrarlo tal cual es mas util que
    // un "error 400" pelado.
    throw new Error(datos.message || datos.error || `Error ${respuesta.status}`);
  }
  return datos;
}

const $ = (sel) => document.querySelector(sel);
let creadores = [];
let estadosWebsub = {};
let seleccionado = null;

// --- Sesión ------------------------------------------------------------------
$('#btn-entrar').addEventListener('click', async () => {
  try {
    await signInWithPopup(auth, new GoogleAuthProvider());
  } catch (err) {
    mostrarError($('#puerta-error'), err.message);
  }
});

$('#btn-salir').addEventListener('click', () => signOut(auth));

onAuthStateChanged(auth, async (usuario) => {
  if (!usuario) return mostrarPuerta();

  const token = await usuario.getIdTokenResult();
  if (token.claims.admin !== true) {
    await signOut(auth);
    return mostrarPuerta(
      'Esa cuenta no tiene permiso de moderación. Pide que te agreguen con scripts/set-admin.js.'
    );
  }

  $('#puerta').hidden = true;
  $('#app').hidden = false;
  escucharDatos();
});

function mostrarPuerta(mensaje) {
  $('#app').hidden = true;
  $('#puerta').hidden = false;
  if (mensaje) mostrarError($('#puerta-error'), mensaje);
}

// --- Escucha en vivo ---------------------------------------------------------
function escucharDatos() {
  onSnapshot(query(collection(db, 'creators'), orderBy('name')), (snap) => {
    creadores = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    pintarRoster();
    pintarResumen();
  });

  onSnapshot(collection(db, 'websub'), (snap) => {
    estadosWebsub = {};
    snap.docs.forEach((d) => { estadosWebsub[d.id] = d.data(); });
    pintarRoster();
    pintarResumen();
    if (seleccionado) pintarSenal(seleccionado);
  });

  onSnapshot(query(collection(db, 'videos'), orderBy('publishedAt', 'desc'), limit(40)), (snap) => {
    pintarVideos(snap.docs.map((d) => ({ id: d.id, ...d.data() })));
  });

  onSnapshot(
    query(collection(db, 'reports'), where('resolved', '==', false), orderBy('createdAt', 'desc'), limit(50)),
    (snap) => pintarReportes(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
  );
}

// --- Roster ------------------------------------------------------------------
function pintarRoster() {
  const cont = $('#lista-creadores');
  cont.innerHTML = '';

  if (!creadores.length) {
    cont.innerHTML = '<p class="ayuda" style="padding:16px">Todavía no hay creadores. Agrega el primero.</p>';
    return;
  }

  creadores.forEach((c) => {
    const canal = (c.platforms?.youtube || {}).channelId;
    const estado = canal ? (estadosWebsub[canal] || {}).estado : null;

    const fila = document.createElement('button');
    fila.className = 'fila-creador';
    fila.setAttribute('aria-current', seleccionado?.id === c.id ? 'true' : 'false');
    fila.innerHTML = `
      <span class="led ${claseLed(c, estado)}"></span>
      <span>
        <span class="nombre">${escapar(c.name)}</span>
        <span class="meta">${escapar(c.category || 'otros')} · ${textoEstado(c, estado)}</span>
      </span>`;
    fila.addEventListener('click', () => seleccionar(c));
    cont.appendChild(fila);
  });
}

function claseLed(c, estado) {
  if (c.active === false) return 'led-apagada';
  if (estado === 'activa') return 'led-activa';
  if (estado === 'pendiente_verificacion') return 'led-pendiente';
  if (estado === 'error') return 'led-error';
  return '';
}

function textoEstado(c, estado) {
  if (c.active === false) return 'oculto';
  if (!(c.platforms?.youtube || {}).channelId) return 'sin YouTube';
  if (estado === 'activa') return 'recibiendo avisos';
  if (estado === 'pendiente_verificacion') return 'verificando…';
  if (estado === 'error') return 'falló la conexión';
  return 'sin conectar';
}

function pintarResumen() {
  const activos = creadores.filter((c) => c.active !== false).length;
  const conSenal = creadores.filter((c) => {
    const canal = (c.platforms?.youtube || {}).channelId;
    return canal && (estadosWebsub[canal] || {}).estado === 'activa';
  }).length;
  $('#resumen').textContent = `${activos} creadores visibles · ${conSenal} recibiendo avisos`;
}

// --- Editor ------------------------------------------------------------------
$('#btn-nuevo').addEventListener('click', () => seleccionar(null, true));

function seleccionar(creador, esNuevo = false) {
  seleccionado = creador;
  $('#sin-seleccion').hidden = true;
  $('#editor').hidden = false;
  $('#editor-titulo').textContent = esNuevo ? 'Creador nuevo' : creador.name;
  $('#btn-borrar').hidden = esNuevo || !creador;
  $('#editor-aviso').hidden = true;
  $('#yt-resultado').textContent = '';

  const c = creador || {};
  const p = c.platforms || {};
  $('#f-nombre').value = c.name || '';
  $('#f-categoria').value = c.category || 'otros';
  $('#f-activo').checked = c.active !== false;
  $('#f-bio').value = c.bio || '';
  $('#f-foto').value = c.photoUrl || '';
  $('#f-yt').value = (p.youtube || {}).channelId || '';
  $('#f-tiktok').value = (p.tiktok || {}).url || '';
  $('#f-twitch').value = (p.twitch || {}).url || '';
  $('#f-instagram').value = (p.instagram || {}).url || '';
  $('#f-spotify').value = (p.spotify || {}).url || '';
  $('#f-patreon').value = (p.patreon || {}).url || '';
  $('#f-web').value = (p.web || {}).url || '';

  pintarSenal(creador);
  pintarRoster();
}

function pintarSenal(creador) {
  const el = $('#estado-senal');
  if (!creador) { el.textContent = ''; return; }
  const canal = (creador.platforms?.youtube || {}).channelId;
  const est = canal ? estadosWebsub[canal] : null;
  if (!est) { el.textContent = 'Sin suscripción de YouTube'; return; }
  const expira = est.expiraEn?.toDate?.();
  el.innerHTML = expira
    ? `<b>${textoEstado(creador, est.estado)}</b> · renueva antes del ${expira.toLocaleDateString('es-MX')}`
    : `<b>${textoEstado(creador, est.estado)}</b>`;
}

// Búsqueda de canal: convierte @handle o URL en el ID canónico UC...,
// que es lo único que WebSub acepta como tema.
$('#btn-buscar').addEventListener('click', async () => {
  const entrada = $('#f-yt').value.trim();
  if (!entrada) return;
  const salida = $('#yt-resultado');
  salida.textContent = 'Buscando…';

  try {
    const data = await api(`/api/admin/canal?query=${encodeURIComponent(entrada)}`);
    $('#f-yt').value = data.channelId;
    if (!$('#f-nombre').value) $('#f-nombre').value = data.title;
    if (!$('#f-foto').value && data.photoUrl) $('#f-foto').value = data.photoUrl;
    salida.textContent = `${data.title} · ${data.channelId}`;
  } catch (err) {
    salida.textContent = err.message;
  }
});

$('#editor').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const btn = $('#btn-guardar');
  btn.disabled = true;
  btn.textContent = 'Guardando…';

  const enlace = (id, plataforma, extra = {}) => {
    const v = $(id).value.trim();
    return v ? { [plataforma]: { url: v, ...extra } } : {};
  };

  const canal = $('#f-yt').value.trim();
  const datos = {
    id: seleccionado?.id,
    name: $('#f-nombre').value.trim(),
    category: $('#f-categoria').value,
    bio: $('#f-bio').value.trim(),
    photoUrl: $('#f-foto').value.trim(),
    active: $('#f-activo').checked,
    platforms: {
      ...(canal ? { youtube: { url: `https://www.youtube.com/channel/${canal}`, channelId: canal } } : {}),
      ...enlace('#f-tiktok', 'tiktok'),
      ...enlace('#f-twitch', 'twitch'),
      ...enlace('#f-instagram', 'instagram'),
      ...enlace('#f-spotify', 'spotify'),
      ...enlace('#f-patreon', 'patreon'),
      ...enlace('#f-web', 'web')
    }
  };

  try {
    const data = await api('/api/admin/creadores', { metodo: 'POST', cuerpo: datos });
    brindis(seleccionado ? 'Creador actualizado' : 'Creador agregado');
    if (data.avisoSuscripcion) {
      mostrarError($('#editor-aviso'), `Se guardó, pero la suscripción falló: ${data.avisoSuscripcion}`);
    }
    seleccionado = creadores.find((c) => c.id === data.id) || null;
  } catch (err) {
    mostrarError($('#editor-aviso'), err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Guardar creador';
  }
});

$('#btn-borrar').addEventListener('click', async () => {
  if (!seleccionado) return;
  if (!confirm(`¿Quitar a ${seleccionado.name} del directorio? Se cancela su suscripción de avisos.`)) return;
  try {
    await api(`/api/admin/creadores/${seleccionado.id}`, { metodo: 'DELETE' });
    brindis('Creador retirado del directorio');
    seleccionado = null;
    $('#editor').hidden = true;
    $('#sin-seleccion').hidden = false;
  } catch (err) {
    mostrarError($('#editor-aviso'), err.message);
  }
});

// --- Publicaciones -----------------------------------------------------------
function pintarVideos(videos) {
  const cont = $('#lista-videos');
  cont.innerHTML = '';

  if (!videos.length) {
    cont.innerHTML = '<p class="ayuda" style="padding:20px 0">Nada todavía. En cuanto un creador publique, aparecerá aquí.</p>';
    return;
  }

  videos.forEach((v) => {
    const fila = document.createElement('div');
    fila.className = 'fila-tabla';
    const fecha = v.publishedAt?.toDate?.();
    const marca =
      v.status === 'moved' ? '<span class="etiqueta etiqueta-movido">movido</span>'
      : v.status === 'removed' ? '<span class="etiqueta etiqueta-retirado">retirado</span>'
      : v.notificado ? '<span class="etiqueta">notificado</span>' : '<span class="etiqueta">detectado</span>';

    fila.innerHTML = `
      <img src="${escapar(v.thumbnailUrl || '')}" alt="">
      <div>
        <p class="titulo">${escapar(v.title || v.videoId)}</p>
        <p class="sub-meta">${escapar(v.creatorName || v.creatorId || '')}${fecha ? ' · ' + fecha.toLocaleString('es-MX') : ''}${v.reportes ? ' · ' + v.reportes + ' reportes' : ''}</p>
      </div>
      <div>${marca}</div>`;

    const btn = document.createElement('button');
    btn.className = 'btn btn-secundario btn-chico';
    btn.textContent = 'Cambiar destino';
    btn.addEventListener('click', () => abrirMover(v));
    fila.appendChild(btn);
    cont.appendChild(fila);
  });
}

let videoAMover = null;

function abrirMover(video) {
  videoAMover = video;
  $('#mover-video').textContent = video.title || video.videoId;
  $('#m-url').value = video.overrideUrl || '';
  $('#dlg-mover').showModal();
}

$('#form-mover').addEventListener('submit', async (ev) => {
  if (ev.submitter?.value !== 'mover') return;
  const url = $('#m-url').value.trim();
  if (!url || !videoAMover) return;

  try {
    await api(`/api/admin/videos/${videoAMover.id}/mover`, {
      metodo: 'POST',
      cuerpo: {
        url,
        platform: $('#m-plataforma').value,
        avisar: $('#m-avisar').checked
      }
    });
    brindis('Destino cambiado');
  } catch (err) {
    alert(err.message);
  }
});

// --- Reportes ----------------------------------------------------------------
function pintarReportes(reportes) {
  const cont = $('#lista-reportes');
  cont.innerHTML = '';

  if (!reportes.length) {
    cont.innerHTML = '<p class="ayuda" style="padding:20px 0">Sin reportes pendientes.</p>';
    return;
  }

  reportes.forEach((r) => {
    const fecha = r.createdAt?.toDate?.();
    const fila = document.createElement('div');
    fila.className = 'fila-tabla';
    fila.style.gridTemplateColumns = 'minmax(0,1fr) 200px';
    fila.innerHTML = `
      <div>
        <p class="titulo">${escapar(r.reason)}</p>
        <p class="sub-meta">video ${escapar(r.videoId || '—')} · creador ${escapar(r.creatorId || '—')}</p>
      </div>
      <p class="sub-meta">${fecha ? fecha.toLocaleString('es-MX') : ''}</p>`;
    cont.appendChild(fila);
  });
}

// --- Navegación --------------------------------------------------------------
document.querySelectorAll('.vista-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.vista-btn').forEach((b) => b.classList.toggle('activo', b === btn));
    ['creadores', 'publicaciones', 'reportes'].forEach((v) => {
      $(`#vista-${v}`).hidden = v !== btn.dataset.vista;
    });
  });
});

// --- Utilidades --------------------------------------------------------------
function escapar(t) {
  return String(t ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function mostrarError(el, mensaje) {
  el.textContent = mensaje;
  el.className = 'aviso aviso-fallo';
  el.hidden = false;
}

let temporizador;
function brindis(mensaje) {
  const el = $('#brindis');
  el.textContent = mensaje;
  el.hidden = false;
  clearTimeout(temporizador);
  temporizador = setTimeout(() => { el.hidden = true; }, 2600);
}
