package com.tuempresa.relay.config;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Las apps y el panel mandan el ID token de Firebase en la cabecera
 * Authorization. Este filtro lo verifica contra Google y monta la
 * autenticación de Spring.
 *
 * El claim {@code admin} se traduce a ROLE_ADMIN. Es el mismo claim que ya
 * usan las Firestore Security Rules, así que no hay dos fuentes de verdad
 * sobre quién modera.
 */
public class FiltroTokenFirebase extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroTokenFirebase.class);
    private static final String PREFIJO = "Bearer ";

    private final FirebaseAuth auth;

    public FiltroTokenFirebase(FirebaseAuth auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest peticion,
            @NonNull HttpServletResponse respuesta,
            @NonNull FilterChain cadena
    ) throws ServletException, IOException {

        String cabecera = peticion.getHeader("Authorization");

        if (cabecera != null && cabecera.startsWith(PREFIJO)) {
            String token = cabecera.substring(PREFIJO.length()).trim();
            try {
                FirebaseToken decodificado = auth.verifyIdToken(token);
                boolean esAdmin = Boolean.TRUE.equals(decodificado.getClaims().get("admin"));

                List<SimpleGrantedAuthority> permisos = new ArrayList<>();
                permisos.add(new SimpleGrantedAuthority("ROLE_USUARIO"));
                if (esAdmin) {
                    permisos.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }

                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(decodificado.getUid(), null, permisos)
                );

            } catch (FirebaseAuthException e) {
                // Token caducado o falsificado. No respondemos 401 desde aquí:
                // dejamos que las reglas de autorización decidan, porque hay
                // rutas públicas que ni siquiera necesitan token.
                log.debug("Token rechazado: {}", e.getMessage());
            }
        }

        cadena.doFilter(peticion, respuesta);
    }

    /** El webhook no lleva token de Firebase: se autentica por HMAC. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return peticion.getServletPath().startsWith("/websub");
    }
}
