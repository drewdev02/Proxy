package org.uci.proxy.core.engine

import org.uci.proxy.core.engine.handler.DnsPacketHandler
import org.uci.proxy.core.engine.handler.ExitRouterHandler
import org.uci.proxy.core.engine.handler.LocalDnsHandler
import org.uci.proxy.core.engine.handler.SplitDnsHandler
import org.uci.proxy.core.engine.handler.TcpPacketHandler
import org.uci.proxy.core.engine.handler.UdpRejectHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PacketWriter
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.proxy.IpNamer
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.port.NameServiceReporter
import org.uci.proxy.core.tcp.TunTcpStack
import org.uci.proxy.core.traffic.TrafficMeter
import kotlin.time.TimeSource

/** Crea el motor de una sesión, que depende del túnel y del perfil concretos. */
class TunnelEngineFactory(
    private val connectors: List<ProxyConnector>,
    private val randomBytes: RandomBytes,
    private val timeSource: TimeSource,
    private val logger: Logger,
    private val nameReporter: NameServiceReporter = NameServiceReporter.None,
    /** Qué app abrió cada conexión, para las reglas de app. */
    private val owner: ConnectionOwner = ConnectionOwner.Unknown,
) {
    /**
     * @param output interfaz por la que la pila TCP devuelve sus paquetes a las apps.
     * @param scope ámbito de la sesión: en él corren el temporizador TCP y los relés de cada conexión.
     */
    fun create(
        plan: TunAddressPlan,
        output: PacketWriter,
        scope: CoroutineScope,
        profile: ProxyProfile,
        password: String,
        settings: AppSettings = AppSettings(),
        /** Con reglas de salida: cómo repartir las conexiones. Sin ellas, todo va por el proxy. */
        split: SplitRouting? = null,
        /** El DNS de la red local, para abrir sin proxy lo que es interno; null si no se usa. */
        localDns: LocalDns? = null,
        /** Dónde se cuentan los bytes que pasan; null si no se mide. */
        meter: TrafficMeter? = null,
    ): TunnelEngine {
        val fakeDns = FakeDnsTable(plan.subnetBase)
        val connector = connectors.first { it.protocol == profile.protocol }
        val health = ProxyHealth(timeSource)
        val checks = ConnectivityChecks(settings.answerConnectivityChecks, health)
        val relay = TunnelRelay(
            connector, profile, password, logger, health, checks, IpNamer(settings, nameReporter, timeSource),
            meter = meter,
            owner = owner,
        )

        val start = timeSource.markNow()
        val tcp = TunTcpStack(
            output = output,
            scope = scope,
            nowMs = { start.elapsedNow().inWholeMilliseconds },
            randomBytes = randomBytes,
            onAccept = relay::serve,
        )
        tcp.start()

        val dns = DnsPacketHandler(DnsResponder(fakeDns, checks::dnsAddressFor), logger)
        val requeued = Channel<Ipv4Packet>(REQUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
        val local = LocalDnsHandler(localDns, output, scope, logger) { requeued.trySend(it) }
        // El orden importa: DNS antes que el reparto por reglas, y este antes que el proxy y el rechazo genérico de UDP.
        // El de la red local va primero: lo interno no debe acabar ni en el DNS falso ni en el de la VPN.
        val handlers = if (split == null) {
            listOf(local, dns, TcpPacketHandler(tcp, fakeDns), UdpRejectHandler())
        } else {
            listOf(
                local,
                SplitDnsHandler(split, dns) { checks.dnsAddressFor(it) != null },
                ExitRouterHandler(split, fakeDns, logger, owner, scope, meter),
                TcpPacketHandler(tcp, fakeDns, split.link::hostFor),
                UdpRejectHandler(),
            )
        }
        return TunnelEngine(handlers, requeued)
    }

    private companion object {
        const val REQUEUE_CAPACITY = 256
    }
}
