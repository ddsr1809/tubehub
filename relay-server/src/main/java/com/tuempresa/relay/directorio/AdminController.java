package com.tuempresa.relay.directorio;

import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.PushService;
import com.tuempresa.relay.websub.WebSubService;
import com.tuempresa.relay.youtube.YouTubeClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel de moderación.
 *
 * Todo bajo /api/admin exige ROLE_ADMIN, que sale del claim del token de
 * sesión. El primer administrador se nombra con una sentencia SQL; los
 * siguientes, desde aquí.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Pattern ID_CANAL = Pattern.compile("(UC[\\w-]{22})");
    private static final Pattern HANDLE = Pattern.compile("@([\\w.-]+)");

    private final Repositorios.Creadores creadores;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Usuarios usuarios;
    private final Repositorios.Suscripciones suscripciones;
    private final Repositorios.Reportes reportes;
    private final WebSubService websub;
    private final YouTubeClient youtube;
    private final PushService push;

    public AdminController(Repositorios.Creadores creadores,
                           Repositorios.Publicaciones publicaciones,
                           Repositorios.Usuarios usuarios,
                           Repositorios.Suscripciones suscripciones,
                           Repositorios.Reportes reportes,
                           WebSubService websub, YouTubeClient youtube, PushService push) {
        this.creadores = creadores;
        this.publicaciones = publicaciones;
        this.usuarios = usuarios;
        this.suscripciones = suscripciones;
        this.reportes = reportes;
        this.websub = websub;
        this.youtube = youtube;
        this.push = push;
    }

    // -------------------------------------------------------------------------
    // Creadores
    // -------------------------------------------------------------------------

    /** Listado con el estado de la suscripción, que es lo que pinta el testigo. */
    @GetMapping("/creadores")
    @Transactional(readOnly = true)
    public List<Dtos.CreadorAdminDto> listar() {
        Map<String, Suscripcion> estados = new HashMap<>();
        suscripciones.findAll().forEach(s -> estados.put(s.getChannelId(), s));

        return creadores.findAll().stream()
                .sorted(Comparator.comparing(Creador::getNombre))
                .map(c -> {
                    Suscripcion s = c.getCanalDeYouTube() != null
                            ? estados.get(c.getCanalDeYouTube()) : null;

                    return new Dtos.CreadorAdminDto(
                            c.getId(), c.getNombre(), c.getCategoria(), c.getBio(),
                            c.getFotoUrl(), c.isActivo(),
                            Dtos.CreadorDto.de(c).conexiones(),
                            s != null ? s.getEstado() : null,
                            s != null ? s.getExpiraEn() : null,
                            usuarios.cuantosSiguen(c.getId()));
                })
                .toList();
    }

    @PostMapping("/creadores")
    @Transactional
    public Dtos.CreadorGuardado guardar(@Valid @RequestBody Dtos.GuardarCreador peticion) {
        if (!Dtos.CATEGORIAS.contains(peticion.categoriaOtros())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Categoría no válida: " + peticion.categoriaOtros());
        }

        Creador creador = peticion.id() != null
                ? creadores.findById(peticion.id()).orElseGet(Creador::new)
                : new Creador();

        String canalPrevio = creador.getCanalDeYouTube();

        creador.setNombre(peticion.nombre().trim());
        creador.setCategoria(peticion.categoriaOtros());
        creador.setBio(recortar(peticion.bio(), 600));
        creador.setFotoUrl(peticion.fotoUrl());
        creador.setActivo(peticion.estaActivo());
        creador.setActualizadoEn(Instant.now());

        Map<String, Conexion> conexiones = new LinkedHashMap<>();
        peticion.conexionesSeguras().forEach((plataforma, dto) -> {
            if (!Dtos.PLATAFORMAS.contains(plataforma)) return;
            if (dto == null || dto.url() == null || dto.url().isBlank()) return;

            conexiones.put(plataforma, new Conexion(
                    dto.url().trim(),
                    dto.handle() != null ? dto.handle().trim() : null,
                    dto.channelId() != null ? dto.channelId().trim() : null));
        });
        creador.setConexiones(conexiones);

        creadores.saveAndFlush(creador);

        // Sincronizar WebSub si el canal cambió o si se activó o desactivó.
        String canalNuevo = creador.getCanalDeYouTube();
        try {
            if (canalPrevio != null && !canalPrevio.equals(canalNuevo)) {
                websub.desuscribir(canalPrevio);
            }
            if (canalNuevo != null && !canalNuevo.isBlank()) {
                if (creador.isActivo()) websub.suscribir(canalNuevo);
                else websub.desuscribir(canalNuevo);
            }
            return new Dtos.CreadorGuardado(creador.getId(), null);

        } catch (Exception e) {
            // El creador queda guardado aunque el hub falle; la renovación
            // programada vuelve a intentarlo en el siguiente ciclo.
            log.error("No se pudo sincronizar la suscripción de {}", creador.getId(), e);
            return new Dtos.CreadorGuardado(creador.getId(), e.getMessage());
        }
    }

    @DeleteMapping("/creadores/{id}")
    @Transactional
    public Dtos.RespuestaSimple borrar(@PathVariable UUID id) {
        Creador creador = creadores.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese creador ya no existe."));

        String canal = creador.getCanalDeYouTube();
        if (canal != null && !canal.isBlank()) {
            try {
                websub.desuscribir(canal);
            } catch (Exception e) {
                log.error("Baja del hub fallida para {}", canal, e);
            }
        }

        creadores.delete(creador);
        return Dtos.RespuestaSimple.de("Creador retirado del directorio.");
    }

    /**
     * Busca los datos públicos de un canal para prellenar el formulario.
     * Acepta un ID UC..., un @handle o una URL completa.
     */
    @GetMapping("/canal")
    public Dtos.DatosDeCanal buscarCanal(@RequestParam String query) {
        String entrada = query.trim();
        if (entrada.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escribe un ID de canal, un @handle o una URL.");
        }

        // WebSub exige el ID canónico: los handles no valen como hub.topic.
        String channelId;
        Matcher idDirecto = ID_CANAL.matcher(entrada);

        if (idDirecto.find()) {
            channelId = idDirecto.group(1);
        } else {
            Matcher conArroba = HANDLE.matcher(entrada);
            String handle = conArroba.find()
                    ? conArroba.group(1)
                    : (entrada.startsWith("@") ? entrada.substring(1) : entrada);
            channelId = youtube.resolverHandle(handle);
        }

        if (channelId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró ese canal. Prueba con el ID que empieza por UC.");
        }

        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId);
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El canal existe pero YouTube no devolvió datos.");
        }
        return datos;
    }

    // -------------------------------------------------------------------------
    // Publicaciones y redirección de emergencia
    // -------------------------------------------------------------------------

    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> recientes(@RequestParam(defaultValue = "40") int limite) {
        List<Publicacion> lista = publicaciones.findAllByOrderByPublicadoEnDesc(
                PageRequest.of(0, Math.min(limite, 200)));

        Map<UUID, String> nombres = new HashMap<>();
        creadores.findAllById(lista.stream().map(Publicacion::getCreadorId).distinct().toList())
                 .forEach(c -> nombres.put(c.getId(), c.getNombre()));

        return lista.stream()
                .map(p -> Dtos.PublicacionDto.de(p, nombres.get(p.getCreadorId())))
                .toList();
    }

    /**
     * Cuando una plataforma tumba un video por un falso positivo, el destino
     * se reemplaza y la audiencia recibe el enlace nuevo. La entidad del
     * creador nunca se pierde: es lo que evita la caída catastrófica de
     * audiencia cuando alguien es desterrado.
     */
    @PostMapping("/videos/{videoId}/mover")
    @Transactional
    public Dtos.RespuestaSimple mover(@PathVariable String videoId,
                                      @Valid @RequestBody Dtos.MoverContenido peticion) {

        Publicacion p = publicaciones.findByVideoId(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese video no está en el directorio."));

        p.setEstado(Publicacion.MOVIDO);
        p.setDestinoUrl(peticion.url());
        p.setDestinoPlataforma(peticion.plataformaDestino());
        publicaciones.save(p);

        if (peticion.debeAvisar()) {
            creadores.findById(p.getCreadorId()).ifPresent(creador ->
                    push.avisarContenidoMovido(creador, videoId, p.getTitulo(),
                            peticion.url(), peticion.plataformaDestino()));
        }

        return Dtos.RespuestaSimple.de("Destino cambiado.");
    }

    // -------------------------------------------------------------------------
    // Reportes
    // -------------------------------------------------------------------------

    @GetMapping("/reportes")
    public List<Reporte> pendientes(@RequestParam(defaultValue = "50") int limite) {
        return reportes.findByResueltoFalseOrderByCreadoEnDesc(
                PageRequest.of(0, Math.min(limite, 200)));
    }

    @PostMapping("/reportes/{id}/resolver")
    @Transactional
    public Dtos.RespuestaSimple resolver(@PathVariable Long id) {
        Reporte reporte = reportes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese reporte no existe."));

        reporte.setResuelto(true);
        return Dtos.RespuestaSimple.de("Reporte marcado como resuelto.");
    }

    // -------------------------------------------------------------------------
    // Administradores
    // -------------------------------------------------------------------------

    @PostMapping("/administradores")
    @Transactional
    public Dtos.RespuestaSimple nombrar(@RequestParam String correo) {
        Usuario usuario = usuarios.findByEmail(correo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese correo no tiene cuenta todavía. Pide que entre una vez primero."));

        usuario.setEsAdmin(true);
        log.info("Rol de administrador otorgado a {}", usuario.getId());

        return Dtos.RespuestaSimple.de(
                "Listo. Pide a esa persona que cierre sesión y vuelva a entrar.");
    }

    private String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
