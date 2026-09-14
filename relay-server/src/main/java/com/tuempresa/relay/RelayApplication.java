package com.tuempresa.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Servidor de seguimiento de creadores.
 *
 * Dos anotaciones que necesitan explicacion:
 *
 * UserDetailsServiceAutoConfiguration se excluye porque no hay usuarios en
 * memoria: la autenticacion la hace FiltroJwt con nuestros propios tokens. Sin
 * la exclusion, Spring genera una contrasena aleatoria en cada arranque y la
 * escupe en el registro, lo que confunde a quien lea los logs.
 *
 * considerNestedRepositories hace falta porque los repositorios viven como
 * interfaces anidadas dentro de Repositorios.java. El escaner de Spring Data
 * las ignora por defecto, y el sintoma es desconcertante: arranca todo bien,
 * el log dice "Found 0 JPA repository interfaces" sin marcarlo como error, y
 * despues falla al construir el primer controlador que pide un repositorio.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
@EnableJpaRepositories(considerNestedRepositories = true)
public class RelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}