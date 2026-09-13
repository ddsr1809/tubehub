# Servidor Relé — Java, Spring Boot y PostgreSQL

Backend de seguimiento de creadores. Recibe los avisos de YouTube por WebSub, los enriquece con la Data API y despacha las notificaciones push.

**Todo vive en tu VPS.** Java 21, Spring Boot 3.4, PostgreSQL. Sin Firestore, sin Firebase Auth, sin dependencias de pago.

---

## Qué cuesta dinero

Tu VPS. Nada más.

| Pieza | Dónde vive | Costo |
|---|---|---|
| Base de datos | Tu VPS (PostgreSQL) | 0 |
| Sesiones y cuentas | Tu VPS (JWT propio) | 0 |
| Detección de videos | WebSub | 0 |
| Metadatos de video | YouTube Data API | 0 (10.000 unidades/día) |
| Notificaciones push | FCM | 0, ilimitado |

**FCM es lo único que sigue siendo de Google, y no hay alternativa:** Android e iOS solo aceptan notificaciones a través de sus propios canales. Necesitas un proyecto de Firebase en el plan Spark, que no pide tarjeta y no puede generar una factura.

Lo caro de verdad —ancho de banda de video, transcodificación, almacenamiento— lo siguen pagando YouTube, TikTok y compañía, porque esta app nunca reproduce contenido.

---

## Qué cambió respecto a la versión con Firestore

**Las apps ya no leen la base de datos directamente.** Antes hablaban con Firestore y recibían actualizaciones en vivo; ahora preguntan a este servidor por REST. En la práctica se nota poco: la novedad llega por push, y la app refresca al abrirse. El feed no es una pantalla que la gente mire fijamente esperando que cambie.

**La autenticación es nuestra.** Verificamos los tokens de Google y Apple contra sus claves públicas —exactamente lo que hacía Firebase Auth por debajo— y emitimos nuestro propio JWT. Las cuentas anónimas se crean con el identificador del dispositivo.

**Las apps necesitan una capa de datos nueva.** Es un trabajo real, no un ajuste de cinco líneas. Las rutas están documentadas abajo.

---

## La API

Todas las rutas bajo `/api` esperan el token de sesión:

```
Authorization: Bearer <token>
```

### Sesión

| Método | Ruta | Para qué |
|---|---|---|
| `POST` | `/api/auth/anonimo` | Sesión invisible al abrir la app. Body: `{deviceId}` |
| `POST` | `/api/auth/google` | Body: `{token, deviceId}` con el idToken de Google |
| `POST` | `/api/auth/apple` | Body: `{token, deviceId, authorizationCode}` |
| `POST` | `/api/auth/renovar` | Token nuevo antes de que caduque |

### Apps

| Método | Ruta | Para qué |
|---|---|---|
| `GET` | `/api/creadores?categoria=` | El directorio curado |
| `GET` | `/api/creadores/{id}` | Un perfil |
| `GET` | `/api/publicaciones?limite=` | Novedades de quienes sigue |
| `GET` | `/api/perfil` | Favoritos y preferencias |
| `PUT` | `/api/favoritos/{creadorId}` | Seguir |
| `DELETE` | `/api/favoritos/{creadorId}` | Dejar de seguir |
| `PUT` | `/api/preferencias` | Tamaño de letra, tema, avisos |
| `POST` | `/api/reportes` | Enlace roto |
| `DELETE` | `/api/cuenta` | Borrado definitivo |

### Moderación

| Método | Ruta |
|---|---|
| `GET` `POST` | `/api/admin/creadores` |
| `DELETE` | `/api/admin/creadores/{id}` |
| `GET` | `/api/admin/canal?query=` |
| `GET` | `/api/admin/publicaciones` |
| `POST` | `/api/admin/videos/{videoId}/mover` |
| `GET` | `/api/admin/reportes` |
| `POST` | `/api/admin/administradores?correo=` |

### Sin token

`POST /websub` (firma HMAC), `GET /websub` (verificación del hub), `POST /internal/renovar` (cabecera `X-Token-Interno`), `GET /actuator/health`.

**Una nota sobre los topics de FCM.** Seguir a alguien guarda el favorito aquí, pero la suscripción al topic la hace la app en el teléfono. Los topics son por aparato, no por cuenta, y el servidor no puede suscribir a nadie en su nombre. Si la app no llama a `subscribeToTopic`, el usuario verá el creador marcado y no recibirá nada.

---

## Desplegar en tu VPS

Lo más corto es Docker Compose: levanta PostgreSQL y el servidor juntos.

```bash
git clone tu-repo && cd relay-server
cp .env.example .env
nano .env          # rellena los valores
docker compose up -d
docker compose logs -f servidor
```

Genera cada secreto por separado:

```bash
openssl rand -hex 32
```

Hacen falta cuatro distintos: `DB_CLAVE`, `JWT_SECRETO`, `WEBSUB_SECRETO`, `WEBSUB_TOKEN_CALLBACK` y `TOKEN_INTERNO`.

### El proxy con HTTPS

El servidor escucha en `127.0.0.1:8080`, no en la IP pública. Necesitas algo delante que resuelva TLS, porque el hub de Google no acepta callbacks en HTTP plano. Con Caddy son dos líneas y el certificado se renueva solo:

```caddyfile
relay.tudominio.com {
    reverse_proxy 127.0.0.1:8080
}
```

### La base de datos no se expone

En `docker-compose.yml`, el servicio `db` no tiene `ports`. Solo la alcanza el servidor por la red interna de Docker. Si necesitas entrar con `psql` desde tu portátil, hazlo por un túnel SSH:

```bash
ssh -L 5432:localhost:5432 usuario@tu-vps
```

Abrir el 5432 a internet es de las formas más rápidas de que te vacíen la base.

---

## Arrancar en local

Sin Docker, con un PostgreSQL que ya tengas:

```bash
createdb relay
cp .env.example .env      # y rellena
export $(grep -v '^#' .env | xargs)
./gradlew bootRun
```

Flyway crea las tablas al arrancar. No hace falta ejecutar ningún SQL a mano.

Comprueba:

```bash
curl http://localhost:8080/actuator/health
```

### Probar el webhook

El hub necesita una URL pública con HTTPS:

```bash
ngrok http 8080
# pon la URL en RELAY_URL_PUBLICA y reinicia
```

La URL de ngrok cambia en cada reinicio con el plan gratuito. Cuando pase, actualiza la variable y vuelve a suscribir todo:

```bash
curl -X POST https://TU-SERVIDOR/internal/renovar -H "X-Token-Interno: EL_VALOR"
```

---

## El primer administrador

No hay forma de nombrarlo desde la API, porque haría falta ser administrador para hacerlo. Se hace una vez con SQL:

```bash
docker compose exec db psql -U relay -d relay \
  -c "update usuarios set es_admin = true where email = 'tu-correo@gmail.com';"
```

Antes tienes que haber entrado una vez con esa cuenta de Google para que el usuario exista. Después, cierra sesión y vuelve a entrar: el permiso solo aparece en un token nuevo.

Los siguientes administradores ya se nombran desde `POST /api/admin/administradores?correo=`.

---

## Pruebas

```bash
./gradlew test
```

Once pruebas sobre el camino crítico: que la validación HMAC rechace cuerpos manipulados, firmas de otro secreto y algoritmos no permitidos, y que el parseo del Atom saque bien el `videoId` pese a los prefijos de namespace. No levantan Spring ni tocan la base de datos.

---

## Copias de seguridad

Nadie lo hace hasta que lo necesita. Un cron diario basta:

```bash
docker compose exec -T db pg_dump -U relay relay | gzip > relay-$(date +%F).sql.gz
```

Lo que de verdad duele perder no son las publicaciones —esas vuelven a llegar— sino las cuentas y los favoritos de la gente.

---

## Cambiar el esquema más adelante

Flyway lleva la cuenta de las migraciones aplicadas. **Nunca edites `V1__esquema_inicial.sql`**: crea un `V2__lo_que_sea.sql` al lado. Editar una migración ya aplicada rompe el arranque con un error de checksum que desconcierta bastante la primera vez.

---

## Cosas que se rompen y cómo notarlo

**Las notificaciones dejan de llegar a los diez días.** El arrendamiento de WebSub caducó. En los registros debe aparecer "Ciclo de renovación terminado" cada cuatro días.

**El testigo del panel se queda en ámbar.** El hub no pudo verificar el webhook. Casi siempre es `RELAY_URL_PUBLICA` mal puesta, o que el proxy no está pasando bien la petición.

**`JWT_SECRETO debe tener al menos 32 caracteres`.** HMAC-SHA256 lo exige. El servidor lo comprueba al arrancar en vez de fallar en el primer inicio de sesión.

**`Schema-validation: missing table`.** Hibernate valida al arrancar que las entidades cuadran con las tablas. Si sale esto, Flyway no llegó a aplicar las migraciones: revisa la conexión a la base.

**Firmas inválidas en los registros.** El `WEBSUB_SECRETO` actual no coincide con el que se usó al suscribirse. Cambiarlo invalida todas las suscripciones: hay que rehacerlas con `/internal/renovar`.

**Todos los usuarios pierden la sesión a la vez.** Cambiaste `JWT_SECRETO`. Los tokens viejos dejan de validar y las apps vuelven a entrar como anónimas. Las cuentas siguen ahí; solo hay que iniciar sesión otra vez.
