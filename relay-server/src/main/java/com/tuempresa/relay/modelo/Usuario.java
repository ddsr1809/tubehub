package com.tuempresa.relay.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Cuenta de una persona que usa la app.
 *
 * Nadie ve una pantalla de registro al abrir: se crea una cuenta anonima
 * ligada al identificador del dispositivo. Una sola equivocacion en el teclado
 * durante el alta basta para que una persona mayor abandone el producto. Solo
 * cuando ya hay favoritos que valga la pena conservar se ofrece enlazarla con
 * Google o Apple.
 */
@Entity
@Table(name = "usuarios")
public class Usuario {

    public static final String ANONIMO = "anonimo";
    public static final String GOOGLE = "google";
    public static final String APPLE = "apple";

    @Id
    @GeneratedValue
    private UUID id;

    /** Identificador que genera la app en la primera ejecucion. */
    @Column(name = "device_id", unique = true)
    private String deviceId;

    @Column(nullable = false)
    private String proveedor = ANONIMO;

    /** El 'sub' del token de Google o Apple. Estable aunque cambie el correo. */
    @Column(name = "proveedor_sub")
    private String proveedorSub;

    private String email;

    @Column(name = "es_admin", nullable = false)
    private boolean esAdmin = false;

    @Column(name = "escala_texto", nullable = false)
    private String escalaTexto = "normal";

    @Column(nullable = false)
    private String tema = "sistema";

    @Column(nullable = false)
    private boolean avisos = true;

    /**
     * Refresh token de Apple. Sin el guardado no se puede revocar el vinculo
     * al borrar la cuenta, y Apple rechaza la app por incumplir la Guideline
     * 5.1.1(v).
     */
    @Column(name = "apple_refresh", columnDefinition = "text")
    private String appleRefresh;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "favoritos", joinColumns = @JoinColumn(name = "usuario_id"))
    @Column(name = "creador_id")
    private Set<UUID> favoritos = new LinkedHashSet<>();

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "visto_en", nullable = false)
    private Instant vistoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getProveedor() { return proveedor; }
    public void setProveedor(String proveedor) { this.proveedor = proveedor; }

    public String getProveedorSub() { return proveedorSub; }
    public void setProveedorSub(String proveedorSub) { this.proveedorSub = proveedorSub; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isEsAdmin() { return esAdmin; }
    public void setEsAdmin(boolean esAdmin) { this.esAdmin = esAdmin; }

    public String getEscalaTexto() { return escalaTexto; }
    public void setEscalaTexto(String escalaTexto) { this.escalaTexto = escalaTexto; }

    public String getTema() { return tema; }
    public void setTema(String tema) { this.tema = tema; }

    public boolean isAvisos() { return avisos; }
    public void setAvisos(boolean avisos) { this.avisos = avisos; }

    public String getAppleRefresh() { return appleRefresh; }
    public void setAppleRefresh(String appleRefresh) { this.appleRefresh = appleRefresh; }

    public Set<UUID> getFavoritos() { return favoritos; }
    public void setFavoritos(Set<UUID> favoritos) {
        this.favoritos = favoritos != null ? favoritos : new LinkedHashSet<>();
    }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getVistoEn() { return vistoEn; }
    public void setVistoEn(Instant vistoEn) { this.vistoEn = vistoEn; }

    @Transient
    public boolean esAnonimo() { return ANONIMO.equals(proveedor); }
}
