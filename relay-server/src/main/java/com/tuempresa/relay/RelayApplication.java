package com.tuempresa.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Servidor de seguimiento de creadores.
 *
 * Excluimos UserDetailsServiceAutoConfiguration porque no hay usuarios en
 * memoria: la autenticacion la hace FiltroJwt con nuestros propios tokens.
 * Sin esta exclusion, Spring genera una contrasena aleatoria en cada arranque
 * y la escupe en el registro, lo que confunde a quien lea los logs.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class RelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
