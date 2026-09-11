'use strict';

const jwt = require('jsonwebtoken');
const { HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const {
  admin,
  db,
  APPLE_PRIVATE_KEY,
  APPLE_TEAM_ID,
  APPLE_KEY_ID,
  APPLE_BUNDLE_ID
} = require('./config');

// La Guideline 5.1.1(v) de Apple exige que toda app que permita crear cuenta
// ofrezca el borrado completo desde dentro de la app. No vale mandar un correo,
// abrir un navegador ni llamarlo "desactivar". Y si la cuenta se creo con
// Sign in with Apple, hay que revocar ademas el token federado.

/**
 * Arma el client secret que Apple exige: un JWT firmado con ES256 usando la
 * clave .p8 descargada del portal de desarrollador.
 */
function clientSecretDeApple() {
  const clave = APPLE_PRIVATE_KEY.value();
  const teamId = APPLE_TEAM_ID.value();
  const keyId = APPLE_KEY_ID.value();
  const bundleId = APPLE_BUNDLE_ID.value();

  if (!clave || !teamId || !keyId || !bundleId) {
    throw new Error('Faltan credenciales de Apple (APPLE_PRIVATE_KEY, TEAM_ID, KEY_ID, BUNDLE_ID)');
  }

  const ahora = Math.floor(Date.now() / 1000);
  return jwt.sign(
    {
      iss: teamId,
      iat: ahora,
      exp: ahora + 300, // vida corta: solo se usa para esta peticion
      aud: 'https://appleid.apple.com',
      sub: bundleId
    },
    clave.replace(/\\n/g, '\n'),
    { algorithm: 'ES256', keyid: keyId }
  );
}

/** POST a Apple para extinguir el vinculo federado. */
async function revocarEnApple(refreshToken) {
  const cuerpo = new URLSearchParams({
    client_id: APPLE_BUNDLE_ID.value(),
    client_secret: clientSecretDeApple(),
    token: refreshToken,
    token_type_hint: 'refresh_token'
  });

  const res = await fetch('https://appleid.apple.com/auth/revoke', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: cuerpo.toString()
  });

  if (!res.ok) {
    const detalle = await res.text().catch(() => '');
    throw new Error(`Apple respondio ${res.status}: ${detalle.slice(0, 200)}`);
  }
  return true;
}

/**
 * Guarda el refresh token de Apple en el momento del login.
 * Sin este paso guardado no se puede revocar despues, y Apple rechaza la app.
 * El token vive en una coleccion cerrada que las reglas no exponen al cliente.
 */
async function guardarTokenApple(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sesión no válida.');
  const { authorizationCode } = request.data || {};
  if (!authorizationCode) throw new HttpsError('invalid-argument', 'Falta el código de autorización.');

  const cuerpo = new URLSearchParams({
    client_id: APPLE_BUNDLE_ID.value(),
    client_secret: clientSecretDeApple(),
    code: authorizationCode,
    grant_type: 'authorization_code'
  });

  const res = await fetch('https://appleid.apple.com/auth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: cuerpo.toString()
  });

  if (!res.ok) {
    const detalle = await res.text().catch(() => '');
    logger.error('Canje del código de Apple fallido', { detalle });
    throw new HttpsError('internal', 'No se pudo completar el inicio de sesión con Apple.');
  }

  const datos = await res.json();
  if (datos.refresh_token) {
    await db.collection('privateTokens').doc(request.auth.uid)
      .set({ refreshToken: datos.refresh_token, uid: request.auth.uid }, { merge: true });
  }

  return { ok: true };
}

/**
 * Borrado completo: revoca Apple, limpia Firestore y elimina la cuenta de Auth.
 * Es irreversible y no requiere pasar por soporte.
 */
async function borrarCuenta(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sesión no válida.');
  const uid = request.auth.uid;

  // 1. Revocar el vinculo con Apple, si lo hay.
  const tokenRef = db.collection('privateTokens').doc(uid);
  const tokenDoc = await tokenRef.get();
  if (tokenDoc.exists && tokenDoc.data().refreshToken) {
    try {
      await revocarEnApple(tokenDoc.data().refreshToken);
      logger.info('Vínculo con Apple revocado', { uid });
    } catch (err) {
      // Si Apple falla seguimos adelante con el borrado local: dejar la cuenta
      // a medio borrar seria peor para el usuario. Queda registrado para
      // reintentarlo desde el panel.
      logger.error('Revocación en Apple fallida', { uid, error: err.message });
      await db.collection('reports').add({
        uid,
        reason: 'apple_revoke_failed',
        detalle: err.message,
        resolved: false,
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });
    }
  }

  // 2. Limpiar todo lo que este usuario dejo en la base de datos.
  const lote = db.batch();
  lote.delete(db.collection('users').doc(uid));
  lote.delete(tokenRef);

  const reportes = await db.collection('reports').where('uid', '==', uid).limit(400).get();
  reportes.docs.forEach((d) => lote.delete(d.ref));
  await lote.commit();

  // 3. Eliminar la identidad. Esto invalida cualquier sesión abierta.
  await admin.auth().deleteUser(uid);

  logger.info('Cuenta eliminada por completo', { uid });
  return { ok: true };
}

/**
 * Recolector de basura de cuentas anonimas inactivas.
 * Evita acumular identidades huérfanas de gente que abrio la app una vez.
 */
async function purgarAnonimasInactivas(dias = 30) {
  const corte = Date.now() - dias * 24 * 60 * 60 * 1000;
  let pageToken;
  let borradas = 0;

  do {
    const lista = await admin.auth().listUsers(1000, pageToken);
    pageToken = lista.pageToken;

    const candidatas = lista.users.filter((u) => {
      const anonima = u.providerData.length === 0;
      const ultimo = new Date(u.metadata.lastRefreshTime || u.metadata.creationTime).getTime();
      return anonima && ultimo < corte;
    });

    for (const u of candidatas) {
      await db.collection('users').doc(u.uid).delete().catch(() => {});
      await admin.auth().deleteUser(u.uid).catch(() => {});
      borradas += 1;
    }
  } while (pageToken);

  logger.info('Purga de cuentas anónimas inactivas', { borradas, dias });
  return { borradas };
}

module.exports = { borrarCuenta, guardarTokenApple, purgarAnonimasInactivas, revocarEnApple };
