package com.tuempresa.relay.config;

import com.tuempresa.relay.auth.ServicioJwt;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee el token de sesion de la cabecera Authorization y monta la autenticacion
 * de Spring. El claim 'adm' se traduce a ROLE_ADMIN.
 */
public class FiltroJwt extends OncePerRequestFilter {

    private static final String PREFIJO = "Bearer ";

    private final ServicioJwt jwt;

    public FiltroJwt(ServicioJwt jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest peticion,
            @NonNull HttpServletResponse respuesta,
            @NonNull FilterChain cadena
    ) throws ServletException, IOException {

        String cabecera = peticion.getHeader("Authorization");

        if (cabecera != null && cabecera.startsWith(PREFIJO)) {
            jwt.verificar(cabecera.substring(PREFIJO.length()).trim())
               .ifPresent(sesion -> {
                   List<SimpleGrantedAuthority> permisos = new ArrayList<>();
                   permisos.add(new SimpleGrantedAuthority("ROLE_USUARIO"));
                   if (sesion.esAdmin()) {
                       permisos.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                   }
                   SecurityContextHolder.getContext().setAuthentication(
                           new UsernamePasswordAuthenticationToken(
                                   sesion.usuarioId(), null, permisos));
               });
        }

        cadena.doFilter(peticion, respuesta);
    }

    /** El webhook no lleva token: se autentica por HMAC. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return peticion.getServletPath().startsWith("/websub");
    }
}
