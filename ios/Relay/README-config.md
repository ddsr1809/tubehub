# Archivos que faltan aquí

Estos dos no están en el repositorio porque contienen datos de tu proyecto:

- **`GoogleService-Info.plist`** — descárgalo de la consola de Firebase
  (Configuración del proyecto → Tus apps → iOS) y colócalo en esta carpeta.
- **`AuthKey_XXXXXXXXXX.p8`** — la clave de APNs. No va en la app: se sube a
  Firebase (Configuración del proyecto → Cloud Messaging → Certificados APNs).
  Sin ella, las notificaciones nunca llegan al iPhone aunque todo lo demás
  esté bien.

Recuerda actualizar en `project.yml`:
- `DEVELOPMENT_TEAM` con tu Team ID de Apple.
- `PRODUCT_BUNDLE_IDENTIFIER` con tu identificador.
- El `CFBundleURLSchemes` con el `REVERSED_CLIENT_ID` que viene dentro de
  `GoogleService-Info.plist`.
