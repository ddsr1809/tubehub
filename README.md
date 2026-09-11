# Relé — Directorio de Creadores

Una app que avisa cuando publican los creadores que sigues y te lleva a la app oficial donde está el video. **No reproduce contenido, no aloja nada y no hace scraping.**

El nombre "Relé" es un marcador de posición: cámbialo por el que prefieras, pero que no contenga "YouTube", "Tube" ni nada parecido. Es un requisito de las guías de marca de Google.

---

## Qué hace cada carpeta

| Carpeta | Qué es | Dónde corre |
|---|---|---|
| `functions/` | El cerebro. Recibe los avisos de YouTube, los enriquece y manda las notificaciones. | Cloud Functions (serverless) |
| `admin/` | El panel donde tú das de alta a los creadores. | Firebase Hosting (web) |
| `android/` | La app de Android, en Kotlin y Jetpack Compose. | Play Store |
| `ios/` | La app de iPhone, en Swift y SwiftUI. | App Store |
| `scripts/` | Utilidades de terminal. | Tu computadora |
| `firestore.rules` | Quién puede leer y escribir qué. | Firebase |

---

## Cómo funciona, en una frase por paso

1. Un creador publica en YouTube.
2. Google avisa a `functions/websub` en menos de un segundo (protocolo WebSub, **0 unidades de cuota**).
3. La función verifica la firma criptográfica, comprueba que no sea un aviso repetido y pide los datos completos del video (**1 unidad de cuota**).
4. Manda una notificación push a todos los que siguen a ese creador.
5. El usuario toca el aviso y la app abre el video en YouTube con un enlace profundo.

El costo mensual con unos cientos de usuarios cae dentro del nivel gratuito de Firebase.

---

## Instalación paso a paso

### 1. Crear el proyecto de Firebase

1. Entra a [console.firebase.google.com](https://console.firebase.google.com) y crea un proyecto.
2. Activa **Firestore Database** (modo producción).
3. Activa **Authentication** y habilita tres proveedores: Anónimo, Google y Apple.
4. Sube al plan **Blaze**. Es obligatorio: las Cloud Functions no salen a internet en el plan gratuito. Con este volumen de uso la factura seguirá siendo prácticamente cero, pero pon un presupuesto de alerta en Google Cloud de todos modos.

### 2. Conseguir la clave de la YouTube Data API

1. Ve a [console.cloud.google.com](https://console.cloud.google.com), selecciona el mismo proyecto.
2. **APIs y servicios → Biblioteca →** activa *YouTube Data API v3*.
3. **Credenciales → Crear credenciales → Clave de API**. Guárdala; la necesitas en el paso 4.

### 3. Instalar las herramientas

```bash
npm install -g firebase-tools
firebase login
cd creator-hub
firebase use --add          # elige tu proyecto
cd functions && npm install && cd ..
```

### 4. Cargar los secretos

```bash
# Genera dos cadenas aleatorias distintas:
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

cd functions
firebase functions:secrets:set YOUTUBE_API_KEY          # la del paso 2
firebase functions:secrets:set WEBSUB_SECRET            # una cadena aleatoria
firebase functions:secrets:set WEBSUB_CALLBACK_TOKEN    # la otra cadena
cp .env.example .env
```

### 5. Desplegar y conectar el webhook

Hay un pequeño baile de dos pasos aquí: la función necesita saber su propia URL, y esa URL solo existe después del primer despliegue.

```bash
firebase deploy --only functions:websub
# La terminal imprime algo como:
#   Function URL (websub): https://websub-a1b2c3d4-uc.a.run.app
```

Copia esa URL, pégala en `functions/.env` como `PUBLIC_BASE_URL`, y despliega todo:

```bash
firebase deploy --only functions,firestore
```

### 6. Abrir el panel y darte permiso de administrador

```bash
firebase deploy --only hosting
```

Antes de entrar, edita `admin/firebase-config.js` con los datos de tu app web (Consola de Firebase → Configuración del proyecto → Tus apps → Web).

Ahora date el rol de administrador:

1. Entra al panel una vez con tu cuenta de Google. Te va a rechazar, es normal: solo queríamos que existiera el usuario.
2. Descarga la clave de servicio (Configuración del proyecto → Cuentas de servicio → Generar clave privada) y guárdala como `scripts/service-account.json`.
3. Ejecuta:

```bash
cd scripts && npm install firebase-admin
node set-admin.js tu-correo@gmail.com
```

4. Cierra sesión, vuelve a entrar. Ya tienes acceso.

### 7. Agregar tu primer creador

En el panel, **Agregar creador**. Pega el `@handle` o la URL del canal en el campo de YouTube y toca **Buscar canal**: eso resuelve el ID canónico `UC...`, que es lo único que WebSub acepta como tema.

Al guardar, el backend manda el handshake al hub de Google. El testigo de la fila pasa a ámbar (verificando) y luego a verde (recibiendo avisos) en unos segundos. Si se queda en ámbar más de un minuto, revisa que `PUBLIC_BASE_URL` esté bien.

### 8. Compilar la app de Android

Lee primero **`android/COMO-ABRIR.md`**: el repositorio no incluye el
`gradle-wrapper.jar` (es binario), así que hay que generarlo una vez con
`gradle wrapper` dentro de `android/`. Sin ese paso, Android Studio usa el
Gradle que tenga instalado y choca con el AGP.

```bash
cd android
gradle wrapper        # solo la primera vez
```

Descarga `google-services.json` de la consola de Firebase (Configuración → Tus apps → Android) y ponlo en `android/app/`.

Después edita `android/app/build.gradle.kts`:
- `applicationId` con tu identificador.
- `WEB_CLIENT_ID` con el `client_id` de tipo 3 que viene dentro de `google-services.json`. Es el error más común: si pones ahí el ID de Android, el login con Google falla sin decir por qué.

Abre **la carpeta `android/`** en Android Studio (no la raíz del proyecto) y dale a Run. En `Settings → Build Tools → Gradle`, comprueba que *Distribution* diga `Wrapper` y que *Gradle JDK* sea un 17.

Para ver las pantallas sin instalar nada en el teléfono, abre `ui/Previews.kt` y pulsa **Split** en la esquina superior derecha del editor. Cada pantalla se renderiza en claro y en oscuro, y varias también con la letra del sistema al 200%: ahí es donde se detecta si un botón se corta antes de que lo sufra un usuario.

Las versiones están fijadas a Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.1.0, una combinación conservadora y conocida. Para subirlas, usa el *AGP Upgrade Assistant* de Android Studio, que cambia el plugin y la versión de Gradle a la vez: son dos cosas que hay que mover juntas o el proyecto deja de sincronizar.

Para el AAB de Play Store:

```bash
./gradlew bundleRelease
```

### 9. Compilar la app de iPhone

El proyecto se genera desde `ios/project.yml`, así no hay un `.xcodeproj` en el repositorio dando conflictos de merge en cada cambio.

```bash
brew install xcodegen
cd ios && xcodegen generate && open Relay.xcodeproj
```

Antes de compilar, lee `ios/Relay/README-config.md`: hacen falta el `GoogleService-Info.plist` y la clave de APNs.

Si prefieres no usar XcodeGen, crea un proyecto de app SwiftUI en Xcode, arrastra la carpeta `ios/Relay` dentro, y añade los paquetes de Firebase y GoogleSignIn con File → Add Package Dependencies. Los valores de `Info.plist` y los entitlements que necesitas están en `project.yml`.

**Las notificaciones no funcionan en el simulador.** Hace falta un iPhone real y una cuenta de desarrollador de pago. Es lo que más sorprende a quien viene de Android.

---

## Probar sin desplegar nada

```bash
cd functions && npm install
node test/webhook.test.js
```

Comprueba las dos cosas que, si se rompen, rompen el producto entero: que la validación de la firma HMAC rechace cuerpos manipulados y firmas de otro secreto, y que el parseo del Atom de YouTube saque bien el `videoId` pese a los prefijos de namespace. Deben pasar las 10.

Para el resto, los emuladores levantan todo en local:

```bash
firebase emulators:start
```

---

## Antes de publicar en las tiendas

Estos cuatro puntos son los que más rechazos causan. El código ya los cubre; lo que falta es la configuración de tu cuenta.

**Sign in with Apple (Guideline 4.8).** Si ofreces Google en iOS, Apple te obliga a ofrecer también su propio inicio de sesión, con la misma visibilidad. `AjustesVista.swift` ya lo pone primero y con la variante primaria. Habilita la capacidad en el portal de desarrollador.

**Borrado de cuenta (Guideline 5.1.1 v).** Ya está en Ajustes y borra de verdad. Para que además revoque el vínculo con Apple, carga estas credenciales:

```bash
firebase functions:secrets:set APPLE_PRIVATE_KEY   # pega el contenido del archivo .p8
# y en functions/.env:
#   APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_BUNDLE_ID
```

**Identidad de marca.** Nada de "Tube" en el nombre, nada de logos parecidos, nada de degradados rojos. El logo de YouTube solo puede aparecer dentro de un botón que diga "Ver en YouTube".

**Curaduría real (Guideline 3.2.2).** La app no puede parecer un índice automático. Como tú apruebas cada creador a mano y no hay buscador abierto, ya estás del lado correcto. Explícalo en las notas para el revisor.

---

## Accesibilidad

`android/.../ui/Tema.kt` y `ios/Relay/Vistas/Tema.swift` codifican los criterios de WCAG 2.2 AA que exige la ley AB 1757 de California ($4,000 USD por barrera detectada, sin periodo de gracia):

- Objetivos táctiles de 48 dp/pt mínimo (56 en acciones principales), con 12 de separación entre botones adyacentes. El mínimo legal es 24; Apple recomienda 44.
- Contraste de 8:1 o más en todo el texto. El mínimo legal es 4.5:1.
- Tres tamaños de letra dentro de la app, que se **suman** al del sistema, con tope en 200%. Sin ese tope, alguien con el teléfono al 200% que además eligiera "Muy grande" llegaría al 290% y rompería el layout.
- Sin contraseñas: la sesión anónima cubre el criterio 3.3.8 (Accessible Authentication).
- Navegación abajo, en la zona del pulgar.
- Pestañas con etiquetas escritas en lugar de iconos que haya que interpretar.

Antes de publicar, recorre las dos apps con VoiceOver y TalkBack encendidos, y con el tamaño de letra del sistema al máximo. Es la única prueba que importa.

---

## Cuota de la YouTube Data API

Tienes 10,000 unidades al día. El diseño gasta ~1 por video publicado.

| Método | Coste | Uso aquí |
|---|---|---|
| WebSub | 0 | Detección de publicaciones |
| `videos.list` | 1 | Rellenar título, descripción y miniatura |
| `channels.list` | 1 | Solo al dar de alta un creador |
| `search.list` | **100** | **Prohibido.** Agotaría el día en 100 llamadas |

Si alguna vez agregas búsqueda, hazlo con `playlistItems.list` sobre la playlist de subidas del canal (1 unidad), nunca con `search.list`.

---

## Cosas que se rompen y cómo notarlo

**Las notificaciones dejan de llegar a los 10 días.** El arrendamiento de WebSub caducó. La función `renovarWebsub` corre cada 4 días para evitarlo; revisa `firebase functions:log --only renovarWebsub`.

**El testigo se queda en ámbar.** El hub no pudo verificar tu webhook. Casi siempre es `PUBLIC_BASE_URL` mal puesta, o que la función no responde el `hub.challenge` como texto plano.

**Llegan avisos duplicados.** No deberían: la transacción de idempotencia en `websub-webhook.js` solo deja pasar el primero. Si pasa, revisa que no tengas dos suscripciones al mismo canal.

**Se envían avisos de videos viejos.** Al suscribirte, el hub reenvía entradas recientes del feed. `MAX_VIDEO_AGE_MS` (6 horas) las descarta. Súbelo o bájalo en `functions/src/config.js`.

**`Task 'prepareKotlinBuildScriptModel' not found in project ':app'`.** Gradle y el Android Gradle Plugin no son compatibles entre sí. Casi siempre significa que el IDE no está usando el wrapper del proyecto. Está explicado en `android/COMO-ABRIR.md`.

---

## Qué falta por hacer

El proyecto está completo de punta a punta, pero estas piezas quedaron fuera a propósito:

- **Detección automática en TikTok, Twitch e Instagram.** Ahora mismo esas plataformas solo tienen enlace en el perfil, no notificación. TikTok no da webhooks públicos; Twitch sí tiene EventSub y sería el siguiente en agregar.
- **Subida de fotos de creador.** El panel pide una URL. Conectar Firebase Storage tomaría poco.
- **Notificaciones programadas** ("tu creador transmite en una hora").
- **Pruebas automatizadas** del webhook más allá de las que ya hay: falta cubrir el enriquecimiento y el envío.
- **Sign in with Apple en Android.** Se puede, vía flujo web, pero añade complejidad y en Android casi nadie lo usa.
- **Tests de interfaz** en ambas apps.

---

## Notas legales que no son código

Necesitas política de privacidad y términos de uso publicados en una URL antes de subir a cualquier tienda. Ambas tiendas los piden y ambas los revisan.

Este documento describe requisitos normativos de forma general; no es asesoría legal. Para AB 1757, la App Store Review y el manejo de datos personales, vale la pena una consulta con un abogado antes de publicar.
