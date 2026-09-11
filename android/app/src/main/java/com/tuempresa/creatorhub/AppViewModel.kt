package com.tuempresa.creatorhub

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuempresa.creatorhub.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class EstadoApp(
    val listo: Boolean = false,
    val creadores: List<Creador> = emptyList(),
    val publicaciones: List<Publicacion> = emptyList(),
    val perfil: Perfil = Perfil(),
    val esAnonimo: Boolean = true,
    val correo: String? = null,
    val ocupado: Boolean = false,
    val mensaje: String? = null,
    val conflicto: AuthRepo.Resultado.Conflicto? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val directorio: DirectorioRepo = DirectorioRepo(),
    private val autenticacion: AuthRepo = AuthRepo()
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoApp())
    val estado: StateFlow<EstadoApp> = _estado.asStateFlow()

    private val perfilFlow = MutableStateFlow(Perfil())

    init {
        viewModelScope.launch {
            // Sesión anónima antes que nada: sin UID no hay lecturas posibles.
            runCatching { autenticacion.iniciarSesionInvisible() }
            _estado.update {
                it.copy(
                    listo = true,
                    esAnonimo = autenticacion.esAnonimo,
                    correo = autenticacion.usuario?.email
                )
            }
            observar()
        }
    }

    private fun observar() {
        viewModelScope.launch {
            directorio.creadores().collect { lista ->
                _estado.update { it.copy(creadores = lista) }
            }
        }

        viewModelScope.launch {
            directorio.perfil().collect { p ->
                perfilFlow.value = p
                _estado.update { it.copy(perfil = p) }
                // Los favoritos viven en la cuenta, pero los topics son por
                // aparato. Un teléfono nuevo no está suscrito a nada.
                directorio.sincronizarTopics(p.favoritos)
            }
        }

        viewModelScope.launch {
            perfilFlow
                .map { it.favoritos }
                .distinctUntilChanged()
                .flatMapLatest { directorio.publicaciones(it) }
                .collect { lista -> _estado.update { it.copy(publicaciones = lista) } }
        }
    }

    fun creador(id: String) = _estado.value.creadores.firstOrNull { it.id == id }

    fun sigue(id: String) = _estado.value.perfil.favoritos.contains(id)

    fun alternarFavorito(id: String) = viewModelScope.launch {
        runCatching { directorio.alternarFavorito(id, sigue(id)) }
            .onFailure { avisar("No se pudo guardar el cambio. Revisa tu conexión.") }
    }

    fun guardarPreferencia(clave: String, valor: Any) = viewModelScope.launch {
        runCatching { directorio.guardarPreferencia(clave, valor) }
    }

    fun reportarEnlace(videoId: String?, creatorId: String?) = viewModelScope.launch {
        runCatching { directorio.reportarEnlaceRoto(videoId, creatorId) }
            .onSuccess { avisar("Gracias. Vamos a revisar ese enlace.") }
            .onFailure { avisar("No se pudo enviar el reporte. Inténtalo más tarde.") }
    }

    // -------------------------------------------------------------------------
    // Cuenta
    // -------------------------------------------------------------------------

    fun vincularConGoogle(contexto: Context) = viewModelScope.launch {
        _estado.update { it.copy(ocupado = true) }
        val resultado = autenticacion.vincularConGoogle(contexto)
        procesar(resultado)
    }

    fun resolverConflicto(contexto: Context) = viewModelScope.launch {
        val pendiente = _estado.value.conflicto ?: return@launch
        _estado.update { it.copy(ocupado = true, conflicto = null) }
        procesar(autenticacion.resolverConflicto(contexto, pendiente.credencialPendiente))
    }

    fun descartarConflicto() = _estado.update { it.copy(conflicto = null) }

    private fun procesar(resultado: AuthRepo.Resultado) {
        val nuevo = when (resultado) {
            is AuthRepo.Resultado.Vinculada ->
                _estado.value.copy(mensaje = "Tu cuenta quedó guardada. Si cambias de teléfono, tus creadores te siguen.")

            is AuthRepo.Resultado.Recuperada -> _estado.value.copy(
                mensaje = if (resultado.favoritosFusionados > 0)
                    "Ya tenías cuenta aquí. Juntamos los ${resultado.favoritosFusionados} creadores de este teléfono con los de antes."
                else
                    "Ya tenías cuenta aquí y volvimos a entrar con ella."
            )

            is AuthRepo.Resultado.Conflicto -> _estado.value.copy(conflicto = resultado)
            is AuthRepo.Resultado.Cancelada -> _estado.value
            is AuthRepo.Resultado.Fallo -> _estado.value.copy(mensaje = resultado.mensaje)
        }

        _estado.value = nuevo.copy(
            ocupado = false,
            esAnonimo = autenticacion.esAnonimo,
            correo = autenticacion.usuario?.email
        )
    }

    fun cerrarSesion(contexto: Context) = viewModelScope.launch {
        runCatching { autenticacion.cerrarSesion(contexto) }
        _estado.update { it.copy(esAnonimo = true, correo = null, mensaje = "Sesión cerrada.") }
    }

    fun borrarCuenta(contexto: Context) = viewModelScope.launch {
        _estado.update { it.copy(ocupado = true) }
        runCatching { autenticacion.borrarCuenta(contexto) }
            .onSuccess { avisar("Cuenta borrada. Puedes seguir usando la app como invitado.") }
            .onFailure { avisar("No se pudo borrar la cuenta: ${it.localizedMessage}") }
        _estado.update { it.copy(ocupado = false, esAnonimo = true, correo = null) }
    }

    private fun avisar(texto: String) = _estado.update { it.copy(mensaje = texto) }

    fun mensajeVisto() = _estado.update { it.copy(mensaje = null) }
}
