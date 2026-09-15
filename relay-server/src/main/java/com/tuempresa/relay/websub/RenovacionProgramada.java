package com.tuempresa.relay.websub;

import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Renovación de arrendamientos por dos caminos, porque dependen del despliegue:
 *
 *  · Servidor siempre encendido (VPS, Kubernetes, Cloud Run con min-instances
 *    ≥ 1): basta con la tarea programada.
 *  · Cloud Run con escalado a cero: una instancia apagada nunca ejecuta una
 *    tarea programada. Ahí se pone relay.renovacion.programada=false y se usa
 *    Cloud Scheduler apuntando a POST /internal/renovar.
 *
 * Olvidar esto es la forma más fácil de que las notificaciones dejen de llegar
 * a los diez días sin un solo error en los registros.
 */
@Component
public class RenovacionProgramada {

    private static final Logger log = LoggerFactory.getLogger(RenovacionProgramada.class);

    private final WebSubService servicio;
    private final RelayProperties config;

    public RenovacionProgramada(WebSubService servicio, RelayProperties config) {
        this.servicio = servicio;
        this.config = config;
    }

    @Scheduled(cron = "${relay.renovacion.cron}", zone = "${relay.renovacion.zona}")
    public void renovar() {
        if (!config.renovacion().programada()) {
            log.debug("Renovación programada desactivada; se espera a Cloud Scheduler");
            return;
        }

        Dtos.ResultadoRenovacion resultado = servicio.renovarTodas();
        if (resultado.fallidos() > 0) {
            log.warn("Quedaron {} suscripciones sin renovar: {}",
                    resultado.fallidos(), resultado.errores());
        }
    }
    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.MINUTES, initialDelay = 2)
    public void repescarPendientes() {
        if (!config.renovacion().programada()) return;
        servicio.reintentarNoActivas();
    }
}

/**
 * Endpoint para Cloud Scheduler. Protegido por una cabecera compartida en lugar
 * de por token de Firebase, porque quien llama es una máquina sin cuenta de
 * usuario.
 */
@RestController
class TareasInternasController {

    private final WebSubService servicio;
    private final RelayProperties config;

    TareasInternasController(WebSubService servicio, RelayProperties config) {
        this.servicio = servicio;
        this.config = config;
    }

    @PostMapping("/internal/renovar")
    public ResponseEntity<Dtos.ResultadoRenovacion> renovar(
            @RequestHeader(name = "X-Token-Interno", required = false) String token
    ) {
        String esperado = config.renovacion().tokenInterno();

        // Si el token no está configurado, la ruta simplemente no existe. Es
        // más seguro que dejarla abierta por descuido.
        if (esperado.isBlank() || !esperado.equals(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(servicio.renovarTodas());
    }
}
