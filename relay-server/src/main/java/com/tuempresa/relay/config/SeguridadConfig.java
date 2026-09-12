package com.tuempresa.relay.config;

import com.google.firebase.auth.FirebaseAuth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    private final RelayProperties config;

    public SeguridadConfig(RelayProperties config) {
        this.config = config;
    }

    @Bean
    public SecurityFilterChain cadenaDeFiltros(HttpSecurity http, FirebaseAuth auth) throws Exception {
        http
                // No hay sesiones ni formularios: cada petición trae su token.
                // Sin estado de sesión que proteger, CSRF no aplica.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> c.configurationSource(fuenteCors()))
                .authorizeHttpRequests(reglas -> reglas
                        // El hub de Google llama sin credenciales. La
                        // autenticidad se comprueba con la firma HMAC del
                        // cuerpo, no con un token.
                        .requestMatchers("/websub").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Tareas internas: protegidas por una cabecera
                        // compartida que valida el propio controlador.
                        .requestMatchers("/internal/**").permitAll()

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/creadores/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(
                        new FiltroTokenFirebase(auth),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    private UrlBasedCorsConfigurationSource fuenteCors() {
        List<String> origenes = Arrays.stream(config.cors().origenes().split(","))
                .map(String::trim)
                .filter(o -> !o.isBlank())
                .toList();

        CorsConfiguration configuracion = new CorsConfiguration();
        configuracion.setAllowedOrigins(origenes);
        configuracion.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuracion.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuracion.setAllowCredentials(true);
        configuracion.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", configuracion);
        return fuente;
    }

    /** Utilidades de sesión para los controladores. */
    public static final class Sesion {

        private Sesion() {}

        /** UID del usuario autenticado, o null si no hay sesión. */
        public static String uidActual() {
            Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
            if (autenticacion == null || !autenticacion.isAuthenticated()) {
                return null;
            }
            Object principal = autenticacion.getPrincipal();
            return principal instanceof String uid ? uid : null;
        }

        /** Igual que el anterior, pero corta la petición si no hay sesión. */
        public static String exigirSesion() {
            String uid = uidActual();
            if (uid == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida.");
            }
            return uid;
        }
    }
}
