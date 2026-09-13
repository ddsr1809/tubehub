-- Esquema inicial.
--
-- Flyway lo aplica solo al arrancar y lleva la cuenta de lo aplicado en la
-- tabla flyway_schema_history. Para cambiar el esquema mas adelante, NUNCA
-- edites este archivo: crea un V2__descripcion.sql al lado. Editar una
-- migracion ya aplicada rompe el arranque con un error de checksum.

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------------------
-- Usuarios
-- ---------------------------------------------------------------------------
-- Nadie ve una pantalla de registro al abrir la app: se crea una cuenta
-- anonima ligada al identificador del dispositivo. Solo cuando el usuario
-- quiere conservar sus datos se enlaza con Google o Apple.
create table usuarios (
    id              uuid primary key default gen_random_uuid(),
    device_id       text unique,
    proveedor       text not null default 'anonimo',   -- anonimo | google | apple
    proveedor_sub   text,                              -- 'sub' del token del proveedor
    email           text,
    es_admin        boolean not null default false,
    escala_texto    text not null default 'normal',
    tema            text not null default 'sistema',
    avisos          boolean not null default true,
    apple_refresh   text,                              -- para revocar al borrar la cuenta
    creado_en       timestamptz not null default now(),
    visto_en        timestamptz not null default now(),
    constraint usuarios_proveedor_unico unique (proveedor, proveedor_sub)
);

create index idx_usuarios_email on usuarios (email) where email is not null;

-- ---------------------------------------------------------------------------
-- Directorio curado
-- ---------------------------------------------------------------------------
-- La entidad es el creador, no el canal: una persona publica en varios sitios
-- y la app los agrupa bajo un solo perfil.
create table creadores (
    id              uuid primary key default gen_random_uuid(),
    nombre          text not null,
    categoria       text not null default 'otros',
    bio             text,
    foto_url        text,
    activo          boolean not null default true,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now()
);

create index idx_creadores_activos on creadores (activo, nombre);

create table conexiones (
    creador_id  uuid not null references creadores (id) on delete cascade,
    plataforma  text not null,
    url         text not null,
    handle      text,
    channel_id  text,
    primary key (creador_id, plataforma)
);

-- Lo consulta el webhook en cada aviso entrante para saber de quien es el
-- canal. Es la busqueda mas frecuente del sistema.
create index idx_conexiones_channel on conexiones (channel_id) where channel_id is not null;

-- ---------------------------------------------------------------------------
-- Publicaciones detectadas
-- ---------------------------------------------------------------------------
create table publicaciones (
    id                  uuid primary key default gen_random_uuid(),
    video_id            text not null unique,
    creador_id          uuid not null references creadores (id) on delete cascade,
    plataforma          text not null default 'youtube',
    titulo              text not null default 'Video nuevo',
    descripcion         text,
    miniatura_url       text,
    url                 text,
    duracion            text,
    tipo                text not null default 'video',      -- video | short
    en_vivo             boolean not null default false,
    estado              text not null default 'ok',         -- ok | moved | removed
    destino_url         text,                               -- redireccion de emergencia
    destino_plataforma  text,
    notificado          boolean not null default false,
    reportes            integer not null default 0,
    publicado_en        timestamptz,
    detectado_en        timestamptz not null default now()
);

create index idx_publicaciones_feed on publicaciones (creador_id, publicado_en desc);
create index idx_publicaciones_recientes on publicaciones (publicado_en desc);

-- ---------------------------------------------------------------------------
-- Favoritos
-- ---------------------------------------------------------------------------
create table favoritos (
    usuario_id  uuid not null references usuarios (id) on delete cascade,
    creador_id  uuid not null references creadores (id) on delete cascade,
    creado_en   timestamptz not null default now(),
    primary key (usuario_id, creador_id)
);

create index idx_favoritos_creador on favoritos (creador_id);

-- ---------------------------------------------------------------------------
-- Estado de las suscripciones WebSub
-- ---------------------------------------------------------------------------
create table suscripciones (
    channel_id      text primary key,
    topic           text not null,
    modo            text not null default 'subscribe',
    estado          text not null,      -- PENDIENTE_VERIFICACION | ACTIVA | CANCELADA | ERROR
    lease_segundos  bigint,
    expira_en       timestamptz,
    solicitado_en   timestamptz not null default now(),
    verificado_en   timestamptz,
    ultimo_error    text
);

-- ---------------------------------------------------------------------------
-- Reportes de enlaces rotos
-- ---------------------------------------------------------------------------
create table reportes (
    id          bigserial primary key,
    usuario_id  uuid references usuarios (id) on delete set null,
    video_id    text,
    creador_id  uuid,
    motivo      text not null default 'enlace_roto',
    detalle     text,
    resuelto    boolean not null default false,
    creado_en   timestamptz not null default now()
);

create index idx_reportes_pendientes on reportes (resuelto, creado_en desc);
