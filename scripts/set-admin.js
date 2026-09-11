#!/usr/bin/env node
'use strict';

// Nombra al primer administrador del panel.
//
// Uso:
//   1. Descarga la clave de servicio desde la consola de Firebase
//      (Configuracion del proyecto > Cuentas de servicio > Generar clave privada)
//      y guardala como scripts/service-account.json  (NO la subas a git).
//   2. Entra una vez al panel con tu cuenta de Google para que exista el usuario.
//   3. node scripts/set-admin.js tu-correo@gmail.com

const admin = require('firebase-admin');
const path = require('path');

const correo = process.argv[2];
if (!correo) {
  console.error('Falta el correo.\nUso: node scripts/set-admin.js tu-correo@gmail.com');
  process.exit(1);
}

const rutaClave = path.join(__dirname, 'service-account.json');
let credencial;
try {
  credencial = require(rutaClave);
} catch (e) {
  console.error(`No encontré ${rutaClave}. Descárgala desde la consola de Firebase.`);
  process.exit(1);
}

admin.initializeApp({ credential: admin.credential.cert(credencial) });

(async () => {
  try {
    const usuario = await admin.auth().getUserByEmail(correo);
    await admin.auth().setCustomUserClaims(usuario.uid, { admin: true });
    console.log(`Listo. ${correo} ya es administrador (uid ${usuario.uid}).`);
    console.log('Cierra sesión y vuelve a entrar en el panel para que el permiso surta efecto.');
  } catch (err) {
    console.error('No se pudo asignar el rol:', err.message);
    process.exit(1);
  }
  process.exit(0);
})();
