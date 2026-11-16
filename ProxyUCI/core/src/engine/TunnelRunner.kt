package org.uci.proxy.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.port.TunnelUnavailableException
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.ProfileRepository

/** Estado del túnel, compartido entre quien lo ejecuta y quien lo observa dentro del mismo proceso. */
class TunnelStateStore : TunnelMonitor {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    fun set(newState: TunnelState) {
        mutableState.value = newState
    }
}

/**
 * Ejecuta un túnel de principio a fin: elige direcciones, crea la interfaz, corre el motor
 * y va publicando el estado.
 */
class TunnelRunner(
    private val profiles: ProfileRepository,
    private val addressPlanner: TunAddressPlanner,
    private val engineFactory: TunnelEngineFactory,
    private val stateStore: TunnelStateStore,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) {
    /**
     * Suspende mientras el túnel esté activo. Termina cuando la interfaz se cierra o al cancelar,
     * y en ambos casos deja el estado en desconectado (o en fallo, si no llegó a arrancar).
     */
    suspend fun run(platformTunnel: PlatformTunnel, profileId: String) {
        val profile = profiles.profiles.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            stateStore.set(TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND))
            return
        }

        val plan = addressPlanner.plan()
        val tun = try {
            platformTunnel.establish(
                TunConfig(
                    sessionName = profile.name,
                    address = plan.tunAddressText,
                    virtualSubnet = plan.subnetText,
                    dnsServer = plan.dnsServerText,
                    mtu = MTU,
                ),
            )
        } catch (e: TunnelUnavailableException) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo crear la interfaz: ${e.message}")
            stateStore.set(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE))
            return
        }

        logger.log(LogLevel.INFO, TAG, "Túnel activo en ${plan.tunAddressText}, DNS ${plan.dnsServerText}")
        stateStore.set(TunnelState.Connected(profileId))
        val engine = engineFactory.create(plan)
        try {
            coroutineScope {
                val loop = launch(dispatchers.background) { engine.run(tun) }
                try {
                    loop.join()
                } catch (e: CancellationException) {
                    // La lectura de la interfaz es bloqueante y no atiende a la cancelación:
                    // solo cerrar la interfaz la despierta.
                    tun.close()
                    throw e
                }
            }
        } finally {
            tun.close()
            stateStore.set(TunnelState.Disconnected)
            logger.log(LogLevel.INFO, TAG, "Túnel detenido")
        }
    }

    private companion object {
        const val TAG = "Tunnel"
        const val MTU = 1500
    }
}
