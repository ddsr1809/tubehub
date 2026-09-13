package com.tuempresa.relay.auth;

import com.tuempresa.relay.cuenta.AppleService;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Usuario;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Alta y enlace de cuentas.
 *
 * El flujo completo, en orden:
 *
 *   1. La app arranca y llama a /anonimo con el identificador del dispositivo.
 *      Se crea una cuenta al vuelo y se devuelve un token. Cero fricción.
 *   2. Si el usuario decide guardar sus datos, la app hace el inicio de sesión
 *      nativo con Google o Apple y nos manda el token resultante junto con su
 *      deviceId. Verificamos el token contra las claves públicas del proveedor.
 *   3. Si ese proveedor ya tenía cuenta aquí (teléfono anterior), nos pasamos
 *      a ella y arrastramos los favoritos de la anónima. Si no, convertimos la
 *      anónima en permanente.
 *
 * El paso 3 es el que evita que alguien pierda sus creadores al cambiar de
 * teléfono, y es donde fallan la mayoría de implementaciones.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final Repositorios.Usuarios usuarios;
    private final ServicioJwt jwt;
    private final VerificadorIdentidad verificador;
    private final AppleService apple;

    public AuthController(Repositorios.Usuarios usuarios, ServicioJwt jwt,
                          VerificadorIdentidad verificador, AppleService apple) {
        this.usuarios = usuarios;
        this.jwt = jwt;
        this.verificador = verificador;
        this.apple = apple;
    }

    /** Sesión invisible. Se llama al abrir la app, sin interfaz de por medio. */
    @PostMapping("/anonimo")
    @Transactional
    public Dtos.Sesion anonimo(@Valid @RequestBody Dtos.EntrarAnonimo peticion) {
        Usuario usuario = usuarios.findByDeviceId(peticion.deviceId())
                .orElseGet(() -> {
                    Usuario nuevo = new Usuario();
                    nuevo.setDeviceId(peticion.deviceId());
                    nuevo.setProveedor(Usuario.ANONIMO);
                    return usuarios.save(nuevo);
                });

        usuario.setVistoEn(Instant.now());
        return sesionDe(usuario, false);
    }

    @PostMapping("/google")
    @Transactional
    public Dtos.Sesion google(@Valid @RequestBody Dtos.EntrarConProveedor peticion) {
        VerificadorIdentidad.Identidad identidad = verificador.verificarGoogle(peticion.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No pudimos verificar tu cuenta de Google. Inténtalo otra vez."));

        return enlazar(identidad, peticion.deviceId(), null);
    }

    @PostMapping("/apple")
    @Transactional
    public Dtos.Sesion apple(@Valid @RequestBody Dtos.EntrarConProveedor peticion) {
        VerificadorIdentidad.Identidad identidad = verificador.verificarApple(peticion.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No pudimos verificar tu cuenta de Apple. Inténtalo otra vez."));

        // El authorizationCode se canjea por un refresh token que hay que
        // guardar para poder revocar el vínculo cuando el usuario borre la
        // cuenta. Sin eso, Apple rechaza la app.
        String refresh = null;
        if (peticion.authorizationCode() != null && !peticion.authorizationCode().isBlank()) {
            refresh = apple.canjearCodigo(peticion.authorizationCode());
        }

        return enlazar(identidad, peticion.deviceId(), refresh);
    }

    /**
     * Renueva el token antes de que caduque.
     * La app lo llama al arrancar si le quedan menos de unos días.
     */
    @PostMapping("/renovar")
    @Transactional
    public Dtos.Sesion renovar(@RequestHeader("Authorization") String cabecera) {
        String token = cabecera.startsWith("Bearer ") ? cabecera.substring(7).trim() : cabecera;

        ServicioJwt.Sesion sesion = jwt.verificar(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión caducó. Vuelve a entrar."));

        Usuario usuario = usuarios.findById(sesion.usuarioId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Esa cuenta ya no existe."));

        usuario.setVistoEn(Instant.now());
        return sesionDe(usuario, false);
    }

    // -------------------------------------------------------------------------

    private Dtos.Sesion enlazar(VerificadorIdentidad.Identidad identidad,
                                String deviceId, String appleRefresh) {

        Optional<Usuario> existente =
                usuarios.findByProveedorAndProveedorSub(identidad.proveedor(), identidad.sub());

        Optional<Usuario> anonimo = (deviceId != null && !deviceId.isBlank())
                ? usuarios.findByDeviceId(deviceId).filter(Usuario::esAnonimo)
                : Optional.empty();

        // Caso A: ya tenía cuenta con este proveedor, probablemente de otro
        // teléfono. Nos pasamos a ella y traemos los favoritos de la anónima.
        if (existente.isPresent()) {
            Usuario usuario = existente.get();
            boolean fusionados = false;

            if (anonimo.isPresent() && !anonimo.get().getId().equals(usuario.getId())) {
                Set<UUID> entrantes = anonimo.get().getFavoritos();
                if (!entrantes.isEmpty()) {
                    usuario.getFavoritos().addAll(entrantes);
                    fusionados = true;
                }
                // El dispositivo pasa a apuntar a la cuenta buena y la anónima
                // desaparece: dejarla suelta acumularía cuentas huérfanas.
                usuario.setDeviceId(deviceId);
                usuarios.delete(anonimo.get());
                log.info("Cuenta anónima fusionada en {}", usuario.getId());
            }

            actualizarDatos(usuario, identidad, appleRefresh);
            return sesionDe(usuario, fusionados);
        }

        // Caso B: primer inicio de sesión con este proveedor. Convertimos la
        // cuenta anónima en permanente, conservando todo lo que ya tenía.
        Usuario usuario = anonimo.orElseGet(Usuario::new);
        usuario.setDeviceId(deviceId);
        usuario.setProveedor(identidad.proveedor());
        usuario.setProveedorSub(identidad.sub());
        actualizarDatos(usuario, identidad, appleRefresh);

        return sesionDe(usuarios.save(usuario), false);
    }

    private void actualizarDatos(Usuario usuario, VerificadorIdentidad.Identidad identidad,
                                 String appleRefresh) {
        if (identidad.email() != null && !identidad.email().isBlank()) {
            usuario.setEmail(identidad.email());
        }
        if (appleRefresh != null) {
            usuario.setAppleRefresh(appleRefresh);
        }
        usuario.setVistoEn(Instant.now());
    }

    private Dtos.Sesion sesionDe(Usuario usuario, boolean fusionados) {
        return new Dtos.Sesion(
                jwt.emitir(usuario),
                usuario.getId(),
                usuario.getProveedor(),
                usuario.getEmail(),
                usuario.isEsAdmin(),
                fusionados
        );
    }
}
