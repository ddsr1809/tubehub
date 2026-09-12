// Reemplaza estos valores con los de tu proyecto.
// Consola de Firebase > Configuración del proyecto > Tus apps > App web.
//
// Estas claves son públicas por diseño: no son un secreto. Lo que protege los
// datos son las Firestore Security Rules y la validación del token en el
// servidor Spring Boot.

export const firebaseConfig = {
  apiKey: 'AIza...',
  authDomain: 'tu-proyecto.firebaseapp.com',
  projectId: 'tu-proyecto',
  storageBucket: 'tu-proyecto.appspot.com',
  messagingSenderId: '000000000000',
  appId: '1:000000000000:web:abcdef123456'
};

// URL del servidor Spring Boot. En desarrollo, http://localhost:8080
// Acuérdate de añadir el origen de este panel a CORS_ORIGENES en el servidor,
// o el navegador bloqueará las peticiones antes de que salgan.
export const API_BASE = 'http://localhost:8080';
