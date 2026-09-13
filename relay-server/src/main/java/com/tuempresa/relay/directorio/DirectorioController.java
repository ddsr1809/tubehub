package com.tuempresa.relay.directorio;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.PushService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que consumen las apps de Android e iOS.
 *
 * Antes leían Firestore directamente y recibían actualizaciones en vivo. Ahora
 * preguntan aquí. En la práctica se nota poco: la novedad llega por
 * notificación push, y la app refresca al abrirse o al volver del segundo
 * plano. El feed no es una pantalla que la gente mire fijamente esperando que
 * cambie.
 *
 * Todas las rutas exigen sesión, aunque sea anónima.
 */
@RestController
@RequestMapping("/api")
public class DirectorioController {

    private final Repositorios.Creadores creadores;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Usuarios usuarios;

    public DirectorioController(Repositorios.Creadores creadores,
                                Repositorios.Publicaciones publicaciones,
                                Repositorios.Usuarios usuarios) {
        this.creadores = creadores;
        this.publicaciones = publicaciones;
        this.usuarios = usuarios;
    }

    // -------------------------------------------------------------------------
    // Directorio
    // -------------------------------------------------------------------------

    /**
     * El directorio completo de creadores activos.
     *
     * Sin paginar a propósito: es un catálogo curado a mano, del orden de
     * decenas de entradas. Paginar aquí añadiría complejidad a las dos apps
     * para resolver un problema que no existe. Si algún día pasa de unos
     * cientos, esto es lo primero que hay que cambiar.
     */
    @GetMapping("/creadores")
    public List<Dtos.CreadorDto> listar(@RequestParam(required = false) String categoria) {
        List<Creador> lista = (categoria == null || categoria.isBlank() || "todos".equals(categoria))
                ? creadores.findByActivoTrueOrderByNombreAsc()
                : creadores.findByActivoTrueAndCategoriaOrderByNombreAsc(categoria);

        return lista.stream().map(Dtos.CreadorDto::de).toList();
    }

    @GetMapping("/creadores/{id}")
    public Dtos.CreadorDto uno(@PathVariable UUID id) {
        Creador creador = creadores.findById(id)
                .filter(Creador::isActivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese creador ya no está en el directorio."));

        return Dtos.CreadorDto.de(creador);
    }

    // -------------------------------------------------------------------------
    // Feed
    // -------------------------------------------------------------------------

    /**
     * Novedades de los creadores que sigue el usuario.
     *
     * Nada de recomendaciones ni scroll infinito: solo lo que publicaron las
     * personas que eligió, en orden de tiempo. Esa previsibilidad es la
     * propuesta de valor entera.
     */
    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> feed(@RequestParam(defaultValue = "50") int limite) {
        Usuario usuario = usuarioActual();

        if (usuario.getFavoritos().isEmpty()) {
            return List.of();
        }

        List<Publicacion> lista = publicaciones.delFeed(
                usuario.getFavoritos(), PageRequest.of(0, Math.min(limite, 100)));

        Map<UUID, String> nombres = nombresDe(lista);
        return lista.stream()
                .map(p -> Dtos.PublicacionDto.de(p, nombres.get(p.getCreadorId())))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Perfil y favoritos
    // -------------------------------------------------------------------------

    @GetMapping("/perfil")
    @Transactional(readOnly = true)
    public Dtos.PerfilDto perfil() {
        return Dtos.PerfilDto.de(usuarioActual());
    }

    /**
     * Seguir a un creador.
     *
     * Esto solo guarda el favorito. La suscripción al topic de FCM la hace la
     * app en el dispositivo: los topics son por aparato, no por cuenta, y el
     * servidor no puede suscribir a nadie en su nombre.
     */
    @PutMapping("/favoritos/{creadorId}")
    @Transactional
    public Dtos.RespuestaSimple seguir(@PathVariable UUID creadorId) {
        if (!creadores.existsById(creadorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese creador no existe.");
        }

        Usuario usuario = usuarioActual();
        usuario.getFavoritos().add(creadorId);

        return Dtos.RespuestaSimple.de("Ahora recibes sus avisos.");
    }

    @DeleteMapping("/favoritos/{creadorId}")
    @Transactional
    public Dtos.RespuestaSimple dejarDeSeguir(@PathVariable UUID creadorId) {
        Usuario usuario = usuarioActual();
        usuario.getFavoritos().remove(creadorId);

        return Dtos.RespuestaSimple.de("Dejaste de recibir sus avisos.");
    }

    /**
     * Preferencias de accesibilidad y apariencia.
     *
     * Viven en el servidor y no en el teléfono para que acompañen al usuario
     * si cambia de aparato. Alguien que necesitó poner la letra en "muy
     * grande" no debería tener que volver a descubrir ese ajuste.
     */
    @PutMapping("/preferencias")
    @Transactional
    public Dtos.PerfilDto preferencias(@Valid @RequestBody Dtos.Preferencias peticion) {
        Usuario usuario = usuarioActual();

        if (peticion.escalaTexto() != null) usuario.setEscalaTexto(peticion.escalaTexto());
        if (peticion.tema() != null) usuario.setTema(peticion.tema());
        if (peticion.avisos() != null) usuario.setAvisos(peticion.avisos());

        return Dtos.PerfilDto.de(usuario);
    }

    // -------------------------------------------------------------------------

    private Usuario usuarioActual() {
        return usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
    }

    /** Una sola consulta para los nombres, en vez de una por publicación. */
    private Map<UUID, String> nombresDe(List<Publicacion> lista) {
        List<UUID> ids = lista.stream().map(Publicacion::getCreadorId).distinct().toList();

        Map<UUID, String> nombres = new HashMap<>();
        creadores.findAllById(ids).forEach(c -> nombres.put(c.getId(), c.getNombre()));
        return nombres;
    }
}
