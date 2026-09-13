package com.tuempresa.relay.modelo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorios.
 *
 * Van agrupados como interfaces anidadas porque cada uno tiene tres o cuatro
 * metodos: repartirlos en seis archivos de diez lineas hace mas dificil ver de
 * un vistazo como se consulta la base de datos. Spring Data las detecta igual.
 */
public final class Repositorios {

    private Repositorios() {}

    public interface Creadores extends JpaRepository<Creador, UUID> {

        List<Creador> findByActivoTrueOrderByNombreAsc();

        List<Creador> findByActivoTrueAndCategoriaOrderByNombreAsc(String categoria);

        /**
         * Busca al creador dueno de un canal de YouTube.
         *
         * Es la consulta mas frecuente del sistema: se ejecuta en cada aviso
         * que llega del hub. El indice parcial sobre conexiones.channel_id la
         * resuelve sin recorrer la tabla.
         */
        @Query("""
                select c from Creador c
                join c.conexiones cx
                where key(cx) = 'youtube' and cx.channelId = :canal
                """)
        Optional<Creador> porCanalDeYouTube(@Param("canal") String canal);

        @Query("select c from Creador c join c.conexiones cx where key(cx) = 'youtube' and c.activo = true")
        List<Creador> activosConYouTube();
    }

    public interface Publicaciones extends JpaRepository<Publicacion, UUID> {

        Optional<Publicacion> findByVideoId(String videoId);

        boolean existsByVideoId(String videoId);

        @Query("""
                select p from Publicacion p
                where p.creadorId in :creadores and p.estado <> 'removed'
                order by p.publicadoEn desc nulls last
                """)
        List<Publicacion> delFeed(@Param("creadores") Collection<UUID> creadores, Pageable pagina);

        List<Publicacion> findAllByOrderByPublicadoEnDesc(Pageable pagina);

        @Modifying
        @Query("update Publicacion p set p.reportes = p.reportes + 1 where p.videoId = :videoId")
        void sumarReporte(@Param("videoId") String videoId);
    }

    public interface Usuarios extends JpaRepository<Usuario, UUID> {

        Optional<Usuario> findByDeviceId(String deviceId);

        Optional<Usuario> findByProveedorAndProveedorSub(String proveedor, String sub);

        Optional<Usuario> findByEmail(String email);

        /** Quienes siguen a un creador. Sirve para contar audiencia. */
        @Query("select count(u) from Usuario u join u.favoritos f where f = :creador")
        long cuantosSiguen(@Param("creador") UUID creador);
    }

    public interface Suscripciones extends JpaRepository<Suscripcion, String> {

        List<Suscripcion> findByEstado(String estado);
    }

    public interface Reportes extends JpaRepository<Reporte, Long> {

        List<Reporte> findByResueltoFalseOrderByCreadoEnDesc(Pageable pagina);

        @Modifying
        @Query("delete from Reporte r where r.usuarioId = :usuario")
        void borrarDeUsuario(@Param("usuario") UUID usuario);
    }
}
