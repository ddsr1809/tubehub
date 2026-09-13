package com.tuempresa.relay.config;

import com.tuempresa.relay.auth.ServicioJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    private final RelayProperties config;

    public SeguridadConfig(RelayProperties config) {
        this.config = config;
    }

    @Bean
    public SecurityFilterChain cadenaDeFiltros(HttpSecurity http, ServicioJwt jwt) throws Exception {
        http
                // Sin sesiones ni formularios: cada peticion trae su token.
                // Sin estado de sesion que proteger, CSRF no aplica.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> c.configurationSource(fuenteCors()))
                .authorizeHttpRequests(reglas -> reglas
                        // El hub de Google llama sin credenciales; la
                        // autenticidad sale de la firma HMAC del cuerpo.
                        .requestMatchers("/websub").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Alta de sesion: por definicion todavia no hay token.
                        .requestMatchers("/api/auth/anonimo",
                                         "/api/auth/google",
                                         "/api/auth/apple").permitAll()

                        // Tareas internas: cabecera compartida, validada en el
                        // propio controlador.
                        .requestMatchers("/internal/**").permitAll()

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(new FiltroJwt(jwt), UsernamePasswordAuthenticationFilter.class);

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

    /** Utilidades de sesion para los controladores. */
    public static final class Sesion {

        private Sesion() {}

        public static UUID uidActual() {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || !a.isAuthenticated()) return null;
            return a.getPrincipal() instanceof UUID uid ? uid : null;
        }

        public static UUID exigir() {
            UUID uid = uidActual();
            if (uid == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida.");
            }
            return uid;
        }
    }
}
