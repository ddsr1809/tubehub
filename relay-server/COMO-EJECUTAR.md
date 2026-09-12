# Servidor Relé — Java + Spring Boot

Backend del Directorio de Creadores. Recibe los avisos de YouTube por WebSub, los enriquece con la Data API y despacha las notificaciones push.

**Java 21** (LTS) y **Spring Boot 3.4**. Firestore, Auth y FCM siguen siendo de Firebase; este servidor los usa a través del Admin SDK.

---

## La API

Todas las rutas bajo `/api` esperan el ID token de Firebase:

```
Authorization: Bearer <idToken>
```

El servidor lo verifica contra Google y traduce el claim `admin` a `ROLE_ADMIN`. Es el mismo claim que usan las Firestore Security Rules, así que no hay dos fuentes de verdad sobre quién modera.

| Método | Ruta | Quién |
|---|---|---|
| `GET` `POST` | `/websub` | El hub de Google (firma HMAC, sin token) |
| `POST` | `/api/reportes` | Cualquier usuario con sesión |
| `DELETE` | `/api/cuenta` | El propio usuario |
| `POST` | `/api/apple/token` | El propio usuario |
| `POST` | `/api/admin/creadores` | Moderación |
| `DELETE` | `/api/admin/creadores/{id}` | Moderación |
| `GET` | `/api/admin/canal?query=` | Moderación |
| `POST` | `/api/admin/videos/{id}/mover` | Moderación |
| `POST` | `/api/admin/administradores?correo=` | Moderación |
| `POST` | `/internal/renovar` | Cloud Scheduler (cabecera `X-Token-Interno`) |
| `GET` | `/actuator/health` | Público |

`/websub` es público por necesidad: Google tiene que poder alcanzarlo sin credenciales. La autenticidad se comprueba con la firma HMAC del cuerpo.

---

## Arrancar en local

```bash
cd relay-server
gradle wrapper          # solo la primera vez: genera gradlew y el .jar
cp .env.example .env    # y rellena los valores
export $(grep -v '^#' .env | xargs)
./gradlew bootRun
```

Comprueba que vive:

```bash
curl http://localhost:8080/actuator/health
```

### Sobre el JDK

El proyecto usa toolchains de Gradle con el plugin de Foojay, así que **si no tienes el JDK 21 instalado, Gradle lo descarga solo**. No hace falta que toques nada aunque ya tengas el 17 para Android Studio.

Si prefieres compilar con Java 17, cambia el `21` por `17` en `build.gradle.kts` y quita `spring.threads.virtual.enabled` de `application.yml`: los hilos virtuales solo existen a partir del 21.

### Credenciales de Firebase

Fuera de Google Cloud hace falta una clave de servicio: consola de Firebase → Configuración del proyecto → Cuentas de servicio → Generar clave privada. Guárdala fuera del repositorio y apunta `GOOGLE_APPLICATION_CREDENTIALS` a ella.

Dentro de Google Cloud no hace falta nada: las credenciales se toman del entorno.

### Probar el webhook sin desplegar

El hub de Google necesita una URL pública y con HTTPS. Para desarrollo:

```bash
ngrok http 8080
# y pon la URL que te dé en RELAY_URL_PUBLICA
```

Si además levantas los emuladores de Firebase, ojo: el de Firestore usa el puerto 8080 por defecto, el mismo que Spring Boot. Cambia uno de los dos.

---

## Pruebas

```bash
./gradlew test
```

Once pruebas que cubren lo único que, si se rompe, rompe el producto entero: que la validación HMAC rechace cuerpos manipulados, firmas de otro secreto y algoritmos no permitidos, y que el parseo del Atom saque bien el `videoId` pese a los prefijos de namespace.

No levantan el contexto de Spring ni tocan Firebase, así que corren en milisegundos. Si alguna falla, no despliegues.

---

## Desplegar

### Cloud Run

```bash
gcloud run deploy relay-server \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --min-instances 1 \
  --set-env-vars "RELAY_URL_PUBLICA=...,CORS_ORIGENES=..." \
  --set-secrets "WEBSUB_SECRETO=websub-secreto:latest,YOUTUBE_API_KEY=youtube-key:latest"
```

**`--min-instances 1` no es opcional.** Con escalado a cero pasan dos cosas malas:

1. Una instancia apagada nunca ejecuta una tarea `@Scheduled`. Los arrendamientos de WebSub caducan a los diez días y las notificaciones dejan de llegar sin ningún error en los registros. Este es el fallo más difícil de diagnosticar de todo el sistema, porque nada se rompe visiblemente: simplemente deja de pasar.
2. El arranque en frío de Spring Boot tarda varios segundos. El hub de Google tiene tiempos de espera cortos en la verificación de intención y puede descartar la suscripción antes de que el servidor llegue a responder.

Si aun así quieres escalar a cero, pon `RENOVACION_PROGRAMADA=false` y crea el trabajo programado:

```bash
gcloud scheduler jobs create http renovar-websub \
  --schedule "0 4 */4 * *" \
  --time-zone "America/Hermosillo" \
  --uri "https://TU-SERVIDOR/internal/renovar" \
  --http-method POST \
  --headers "X-Token-Interno=EL_VALOR_DE_TOKEN_INTERNO"
```

Seguirás expuesto al problema del arranque en frío durante las verificaciones.

### Cualquier VPS con Docker

```bash
docker build -t relay-server .
docker run -d -p 8080:8080 --env-file .env --restart unless-stopped relay-server
```

Aquí no hay problema de escalado a cero: el contenedor está siempre encendido y la tarea programada funciona tal cual. Ponle un proxy delante (Caddy o nginx) que resuelva HTTPS, porque el hub de Google no acepta callbacks en HTTP plano.

---

## Sobre el costo

Con funciones sin servidor el backend costaba prácticamente cero porque no había nada encendido entre eventos. Con una instancia siempre activa cuentas con un coste fijo: unos 10-15 USD al mes en Cloud Run, 5-6 en un VPS pequeño.

Lo que no cambia es la parte cara: el ancho de banda, la transcodificación y el almacenamiento de video los siguen pagando YouTube, TikTok y compañía. Ese sigue siendo el fundamento de toda la arquitectura.

---

## Cuota de la YouTube Data API

Tienes 10.000 unidades al día. El diseño gasta una por video publicado.

| Método | Coste | Uso aquí |
|---|---|---|
| WebSub | 0 | Detección de publicaciones |
| `videos.list` | 1 | Título, descripción y miniatura |
| `channels.list` | 1 | Solo al dar de alta un creador |
| `search.list` | **100** | **Prohibido.** Agotaría el día en 100 llamadas |

`YouTubeClient` no tiene ningún método que llame a `search.list`, y es deliberado. Si algún día añades búsqueda, hazlo con `playlistItems.list` sobre la playlist de subidas del canal, que cuesta 1.

---

## Cosas que se rompen y cómo notarlo

**Las notificaciones dejan de llegar a los diez días.** El arrendamiento caducó. En los registros debe aparecer "Ciclo de renovación terminado" cada cuatro días. Si no aparece, el servicio está escalando a cero.

**El testigo del panel se queda en ámbar.** El hub no pudo verificar el webhook. Casi siempre es `RELAY_URL_PUBLICA` mal puesta, o que el servidor no responde el `hub.challenge` como texto plano.

**El hub nunca llama.** Comprueba que la URL sea HTTPS y accesible desde fuera:

```bash
curl "https://TU-SERVIDOR/websub?token=TU_TOKEN&hub.mode=subscribe&hub.topic=x&hub.challenge=hola"
```

Debe devolver `No solicitado` (404). Si devuelve `No encontrado`, el token no coincide.

**`Falta RELAY_URL_PUBLICA`.** Literal: falta esa variable. Es la que más se olvida porque solo se conoce después del primer despliegue.

**Firmas inválidas en los registros.** El `WEBSUB_SECRETO` actual no coincide con el que se usó al suscribirse. Cambiar ese valor invalida todas las suscripciones existentes: hay que volver a suscribir todos los canales con `POST /internal/renovar`.

**`No matching toolchains found`.** El plugin de Foojay en `settings.gradle.kts` debería descargar el JDK solo. Si falla, suele ser que la máquina no tiene salida a internet hacia `api.foojay.io`; instala el JDK 21 a mano.
