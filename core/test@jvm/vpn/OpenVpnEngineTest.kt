package org.uci.proxy.core.vpn

import org.uci.proxy.core.model.OvpnConfigError
import org.uci.proxy.core.model.VpnHandshakeIssue
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeCountryEngine
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TcpException
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTarget
import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.IpNameService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.uci.proxy.core.proxy.RefusalReason
import org.uci.proxy.core.port.NetFailure

// Una autoridad autofirmada cualquiera, solo para que el perfil de prueba tenga un <ca> legible.
private val AUTHORITY = """
-----BEGIN CERTIFICATE-----
MIIC9jCCAd6gAwIBAgIURSzDy4VkDsd2NqbfA3uedjbm6kwwDQYJKoZIhvcNAQEL
BQAwGjEYMBYGA1UEAwwPUHJ1ZWJhIFByb3h5VUNJMCAXDTI2MTExODE4NTI0N1oY
DzIxMjYxMDI1MTg1MjQ3WjAaMRgwFgYDVQQDDA9QcnVlYmEgUHJveHlVQ0kwggEi
MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCXI4r/S7/bi+nEZOBUfYwjm9Yi
/85S43VSoHQ3axk7uIoqXBtahP156RhMpRy/+W9+Iecbw5SXG9O70TK788oYTlS/
v1k4UGibgN68k0jOj4C6fzPiqnkrrD6gIJ/fmi1BB/BwhnM/TXcn337q9bOo5t1x
Rs3snfMe/DTx64T3PYP7PNi1zqgB147HBNirzXwx+qjJ8ovBomldicfIPqpIfmxy
45TNc0aEtd0VmimyFmgNjFVeUphyn2aCrQz4vnKQqFoEKbMLtz/1JgIV3fefU3q6
Nz3Ch3jrHSkyuO76BsD78NtYfq4d9FydLDKOsSn/VnWn1S3S0NUjUL1gnIhdAgMB
AAGjMjAwMB0GA1UdDgQWBBQnUdsAswDKvQeYhhWqnzb/jXGBSDAPBgNVHRMBAf8E
BTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBJAb1jhtPH3QS93H9w5OxAfFtUvD6b
WltoMm1vJdwI5+reegkWQpRNf0jwaRR39TjTM8q+pp3fEUDOYQBkyQSYcRTfsTmA
dehg6T51oN0UlQ9zJWCBKnJQ2ULkIniQqQAcjHN3t0i4QwIEwaKkNxxY1vR55qHS
AQFUmbI3nQm8ynEBxikBA2E/SCgBpUVgFfUfU5udGAFfeEuhI9ySZHzJbM68cbmT
m9r2W2Jo3gwWzLVI+5G8u97Ti0QI7tfbaIFYA9mdBGWwZz7DZ16h+2me0BW4ZNw/
NYGIlZfSux9h1bDqLl/kX4lVBMvFTTcqK+qSwST3SSmJRnF9Zv+et82x
-----END CERTIFICATE-----
""".trim()

private const val DAMAGED_AUTHORITY = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"

/** Qué pasa al llegar a los servidores VPN a través del proxy, sin hablar OpenVPN de verdad. */
class OpenVpnEngineTest {
    private val proxy = ProxyProfile("p", "UCI", "10.8.0.20", 3128, ProxyProtocol.HTTP, "andrey")
    private val keyHex = (0 until 256).joinToString("") { it.toString(16).padStart(2, '0') }

    private fun ovpn(
        remotes: String = "remote 192.0.2.10 443\nremote 192.0.2.11 7770",
        extra: String = "",
        authority: String = AUTHORITY,
    ) = "client\ndev tun\nproto tcp\n$remotes\nauth-user-pass\n$extra\n" +
        "<ca>\n$authority\n</ca>\n" +
        "<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\n${keyHex.chunked(32).joinToString("\n")}\n-----END OpenVPN Static key V1-----\n</tls-crypt>\n"

    private fun exit(connector: FakeProxyConnector, available: Boolean = true) = OpenVpnEngine(
        client = OpenVpnClient(JcaOpenVpnCrypto(), SslEngineTlsSessionFactory(), RandomBytes { ByteArray(it) { 7 } }, RecordingLogger(), { 0L }),
        dialer = ProxyDialer(listOf(connector), RecordingLogger()),
        random = RandomBytes { ByteArray(it) { 7 } },
        logger = RecordingLogger(),
        isAvailable = available,
    )

    private fun start(text: String) = CountryExitStart(CountryExit.OpenVpn("pais.ovpn", "vpnuser"), CountryExitSecrets.OpenVpn(text, "vpnpass"))

    private suspend fun OpenVpnEngine.connect(proxy: ProxyProfile, password: String, start: CountryExitStart, settings: AppSettings = AppSettings()) =
        connect(ExitContext(proxy, password, settings), start)

    @Test
    fun `a profile that cannot be used is reported without touching the network`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.AuthRejected)

        val result = exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote 192.0.2.10 1194 udp")))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.OvpnInvalid(OvpnConfigError.NoTcpServers)), result)
        assertEquals(emptyList(), connector.targets)
    }

    @Test
    fun `the servers are tried in the order of the file`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"))

        val result = exit(connector).connect(proxy, "x", start(ovpn()))

        assertEquals(listOf(ProxyTarget("192.0.2.10", 443), ProxyTarget("192.0.2.11", 7770)), connector.targets)
        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnServerBlocked), result, "un 403 es la política del proxy")
    }

    @Test
    fun `when the proxy refuses the first server the next one is tried`() = runTest {
        val closed = FakeTunnelConnection().also { it.end() }
        val connector = FakeProxyConnector { target ->
            if (target.port == 443) ProxyConnectResult.Failed(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "403")) else ProxyConnectResult.Connected(closed)
        }

        val result = exit(connector).connect(proxy, "x", start(ovpn()))

        assertEquals(2, connector.targets.size)
        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnHandshakeFailed(VpnHandshakeIssue.CLOSED)), result, "el segundo abrió pero el servidor no habló")
    }

    private val publicIp = "149.22.82.28"
    private val publicRemotes = "remote $publicIp 7770\nremote $publicIp 8443\nremote $publicIp 443"

    @Test
    fun `a public server IP is asked of the proxy by its sslip name and never by address`() = runTest {
        // El Squid de la UCI rechaza el CONNECT a una IP escrita tal cual y acepta el nombre sslip.io.
        val closed = FakeTunnelConnection().also { it.end() }
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".sslip.io")) ProxyConnectResult.Connected(closed) else ProxyConnectResult.Failed(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"))
        }

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote $publicIp 443")))

        assertEquals(listOf(ProxyTarget("149-22-82-28.sslip.io", 443)), connector.targets)
    }

    @Test
    fun `if the proxy refuses the sslip name the address is tried before giving up on that server`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"))

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote $publicIp 443")))

        assertEquals(
            listOf(ProxyTarget("149-22-82-28.sslip.io", 443), ProxyTarget("149.22.82.28.nip.io", 443), ProxyTarget(publicIp, 443)),
            connector.targets,
            "cada servicio activo por orden y la IP al final",
        )
    }

    @Test
    fun `the next service is tried when the proxy refuses the first one`() = runTest {
        val closed = FakeTunnelConnection().also { it.end() }
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".nip.io")) ProxyConnectResult.Connected(closed) else ProxyConnectResult.Failed(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "403"))
        }

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote $publicIp 443")))

        assertEquals(listOf(ProxyTarget("149-22-82-28.sslip.io", 443), ProxyTarget("149.22.82.28.nip.io", 443)), connector.targets)
    }

    @Test
    fun `the service that worked is asked first for the next server`() = runTest {
        val closed = FakeTunnelConnection().also { it.end() }
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".nip.io")) ProxyConnectResult.Connected(closed) else ProxyConnectResult.Failed(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "403"))
        }

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote $publicIp 443\nremote 149.22.82.29 443")))

        // El primer servidor abrió por nip.io (aunque luego falló el handshake): el segundo ya empieza por ahí.
        assertEquals("149.22.82.29.nip.io", connector.targets[2].host)
    }

    @Test
    fun `the settings decide which services are asked and whether the address is`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "403"))
        val settings = AppSettings(tryRawIp = false, nameServices = listOf(IpNameService.NIP, IpNameService.SSLIP.copy(enabled = false)))

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote $publicIp 443")), settings)

        assertEquals(listOf(ProxyTarget("149.22.82.28.nip.io", 443)), connector.targets)
    }

    @Test
    fun `with names turned off the server is asked for by address`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "403"))

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote $publicIp 443")), AppSettings(nameByIp = false))

        assertEquals(listOf(ProxyTarget(publicIp, 443)), connector.targets)
    }

    @Test
    fun `a server given by name or a private address is not rewritten`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Unreachable(NetFailure.NO_ROUTE, "sin ruta"))

        exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote vpn.ejemplo.org 443\nremote 10.9.0.5 443")))

        assertEquals(listOf(ProxyTarget("vpn.ejemplo.org", 443), ProxyTarget("10.9.0.5", 443)), connector.targets)
    }

    @Test
    fun `port 443 is tried first whatever the order in the file`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Unreachable(NetFailure.NO_ROUTE, "sin ruta"))

        exit(connector).connect(proxy, "x", start(ovpn(remotes = publicRemotes)))

        assertEquals(443, connector.targets.first().port)
        assertEquals(setOf(443, 7770, 8443), connector.targets.map { it.port }.toSet(), "los demás quedan como alternativa")
    }

    @Test
    fun `port 443 still comes first with a random order`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Unreachable(NetFailure.NO_ROUTE, "sin ruta"))

        exit(connector).connect(proxy, "x", start(ovpn(remotes = publicRemotes, extra = "remote-random")))

        assertEquals(443, connector.targets.first().port)
    }

    @Test
    fun `when the proxy policy refuses every server the failure says so`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"))

        val result = exit(connector).connect(proxy, "x", start(ovpn(remotes = publicRemotes)))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnServerBlocked), result)
    }

    @Test
    fun `when nothing answers at all it is unreachable and not blocked`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Unreachable(NetFailure.NO_ROUTE, "sin ruta"))

        val result = exit(connector).connect(proxy, "x", start(ovpn(remotes = publicRemotes)))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnServerUnreachable), result)
    }

    @Test
    fun `a proxy that rejects the credentials stops at once`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.AuthRejected)

        val result = exit(connector).connect(proxy, "x", start(ovpn()))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.ProxyAuthFailed), result)
        assertEquals(1, connector.targets.size, "no tiene sentido probar otro servidor con las mismas credenciales")
    }

    @Test
    fun `a server that closes during the handshake is reported as a failed handshake`() = runTest {
        val connector = FakeProxyConnector { ProxyConnectResult.Connected(FakeTunnelConnection().also { it.end() }) }

        val result = exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote 192.0.2.10 443")))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnHandshakeFailed(VpnHandshakeIssue.CLOSED)), result)
    }

    @Test
    fun `a proxy that cuts the connection mid handshake moves on to the next server`() = runTest {
        val connector = FakeProxyConnector { target ->
            val connection = FakeTunnelConnection()
            if (target.port == 443) connection.fail() else connection.end()
            ProxyConnectResult.Connected(connection)
        }

        val result = exit(connector).connect(proxy, "x", start(ovpn()))

        assertEquals(listOf(ProxyTarget("192.0.2.10", 443), ProxyTarget("192.0.2.11", 7770)), connector.targets)
        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnHandshakeFailed(VpnHandshakeIssue.CLOSED)), result)
    }

    @Test
    fun `a cut connection with the only server is a failed handshake and not an exception`() = runTest {
        val connector = FakeProxyConnector { ProxyConnectResult.Connected(FakeTunnelConnection().also { it.fail() }) }

        val result = exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote 192.0.2.10 443")))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnHandshakeFailed(VpnHandshakeIssue.CLOSED)), result)
    }

    @Test
    fun `an engine that throws is a failure and not an exception for the tunnel`() = runTest {
        val engines = CountryEngines(listOf(FakeCountryEngine(onConnect = { _, _ -> throw TcpException(NetFailure.RESET, "conexión con el proxy perdida") })))

        val result = engines.connect(ExitContext(proxy, "x", AppSettings()), start(ovpn()))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.EngineInternal), result)
    }

    @Test
    fun `a damaged authority certificate is a profile problem and not a server problem`() = runTest {
        val connector = FakeProxyConnector { ProxyConnectResult.Connected(FakeTunnelConnection()) }

        val result = exit(connector).connect(proxy, "x", start(ovpn(remotes = "remote 192.0.2.10 443", authority = DAMAGED_AUTHORITY)))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.OvpnInvalid(OvpnConfigError.InvalidCa)), result)
    }

    @Test
    fun `random order still tries every server`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "403"))

        exit(connector).connect(proxy, "x", start(ovpn(extra = "remote-random")))

        assertEquals(setOf(ProxyTarget("192.0.2.10", 443), ProxyTarget("192.0.2.11", 7770)), connector.targets.toSet())
    }

    @Test
    fun `an engine that is not available here is reported as such`() = runTest {
        val engines = CountryEngines(listOf(exit(FakeProxyConnector.failingWith(ProxyFailure.AuthRejected), available = false)))

        val result = engines.connect(ExitContext(proxy, "x", AppSettings()), start(ovpn()))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.EngineUnavailable), result)
        assertEquals(emptyList(), engines.available)
    }

    @Test
    fun `a Tailscale exit given to the OpenVPN engine is rejected`() = runTest {
        val result = exit(FakeProxyConnector.failingWith(ProxyFailure.AuthRejected))
            .connect(proxy, "x", CountryExitStart(CountryExit.Tailscale(), CountryExitSecrets.Tailscale("k")))

        assertEquals(VpnConnectResult.Failed(TunnelFailure.VpnSecretsMissing), result)
    }
}
