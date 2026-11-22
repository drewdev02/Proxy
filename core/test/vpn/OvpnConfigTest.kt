package org.uci.proxy.core.vpn

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OvpnConfigTest {
    private val keyHex = (0 until 16).joinToString("\n") { line -> (0 until 16).joinToString("") { (line * 16 + it).toString(16).padStart(2, '0') } }

    private fun profile(
        extra: String = "",
        remotes: String = "remote 192.0.2.10 443\nremote 192.0.2.10 7770\nremote 192.0.2.11 8443 udp",
        proto: String = "proto tcp",
        blocks: String = "<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n" +
            "<tls-crypt>\n#\n# 2048 bit OpenVPN static key\n#\n-----BEGIN OpenVPN Static key V1-----\n$keyHex\n-----END OpenVPN Static key V1-----\n</tls-crypt>\n",
    ) = "client\ndev tun\n$proto\n$remotes\nremote-random\ncipher AES-256-GCM\nauth-user-pass\ntun-mtu 1500\n$extra\n$blocks"

    @Test
    fun `a typical commercial profile is read`() {
        val config = parseOvpn(profile())

        assertEquals(listOf(OvpnRemote("192.0.2.10", 443), OvpnRemote("192.0.2.10", 7770)), config.remotes, "solo los servidores TCP")
        assertTrue(config.needsCredentials)
        assertTrue(config.remoteRandom)
        assertEquals("AES-256-GCM", config.cipher)
        assertEquals(1500, config.tunMtu)
        assertTrue(config.caPem.contains("BEGIN CERTIFICATE"))
        assertEquals(256, config.tlsCryptKey.size)
        assertContentEquals(ByteArray(256) { it.toByte() }, config.tlsCryptKey)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val config = parseOvpn("# un comentario\n; otro\n\n" + profile())

        assertEquals(2, config.remotes.size)
    }

    @Test
    fun `a profile with only UDP servers is rejected with a clear message`() {
        val error = assertFailsWith<OvpnConfigException> { parseOvpn(profile(proto = "proto udp", remotes = "remote 192.0.2.10 1194")) }

        assertEquals(OvpnConfigError.NoTcpServers, error.error)
    }

    @Test
    fun `a remote can name its own protocol`() {
        val config = parseOvpn(profile(proto = "proto udp", remotes = "remote 192.0.2.10 443 tcp\nremote 192.0.2.10 1194 udp"))

        assertEquals(listOf(OvpnRemote("192.0.2.10", 443)), config.remotes)
    }

    @Test
    fun `features the client does not implement are rejected`() {
        assertFailsWith<OvpnConfigException> { parseOvpn(profile(extra = "tls-auth ta.key 1")) }
        assertFailsWith<OvpnConfigException> { parseOvpn(profile(extra = "compress lz4-v2")) }
        assertFailsWith<OvpnConfigException> { parseOvpn(profile(extra = "comp-lzo yes")) }
        assertFailsWith<OvpnConfigException> { parseOvpn(profile(extra = "<cert>\nx\n</cert>")) }
        assertFailsWith<OvpnConfigException> { parseOvpn(profile(extra = "dev tap")) }
    }

    @Test
    fun `compression turned off is accepted`() {
        parseOvpn(profile(extra = "comp-lzo no"))
        parseOvpn(profile(extra = "compress stub-v2"))
    }

    @Test
    fun `a profile without authority or tls-crypt is rejected`() {
        assertFailsWith<OvpnConfigException> { parseOvpn(profile(blocks = "")) }
        assertFailsWith<OvpnConfigException> {
            parseOvpn(profile(blocks = "<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n"))
        }
    }

    @Test
    fun `a malformed key or an unclosed block is rejected`() {
        assertFailsWith<OvpnConfigException> {
            parseOvpn(profile(blocks = "<ca>\n-----BEGIN CERTIFICATE-----\n</ca>\n<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\nabcd\n-----END OpenVPN Static key V1-----\n</tls-crypt>"))
        }
        assertFailsWith<OvpnConfigException> { parseOvpn(profile() + "<ca>\nsin cerrar") }
    }

    @Test
    fun `text that is not a client profile is rejected`() {
        assertFailsWith<OvpnConfigException> { parseOvpn("hola mundo") }
    }

    @Test
    fun `a profile without a password prompt does not ask for credentials`() {
        assertFalse(parseOvpn(profile().replace("auth-user-pass\n", "")).needsCredentials)
    }
}

class OpenVpnPushTest {
    private val real = "PUSH_REPLY,redirect-gateway ipv6 def1,explicit-exit-notify,dhcp-option DNS 10.98.0.1,tun-ipv6," +
        "route-gateway 10.98.0.1,topology subnet,ping 10,ping-restart 60,ifconfig 10.98.0.9 255.255.0.0,peer-id 3,cipher AES-256-GCM"

    @Test
    fun `a real push reply is interpreted`() {
        val push = parsePushReply(real)

        assertEquals("10.98.0.9", push.address)
        assertEquals("255.255.0.0", push.netmask)
        assertEquals(listOf("10.98.0.1"), push.dnsServers)
        assertEquals(3, push.peerId)
        assertEquals("AES-256-GCM", push.cipher)
        assertEquals(10, push.pingSeconds)
        assertEquals(60, push.pingRestartSeconds)
        assertTrue(push.redirectGateway)
    }

    @Test
    fun `several DNS servers are kept in order`() {
        val push = parsePushReply("$real,dhcp-option DNS 10.98.0.2")

        assertEquals(listOf("10.98.0.1", "10.98.0.2"), push.dnsServers)
    }

    @Test
    fun `missing values take sensible defaults`() {
        val push = parsePushReply("PUSH_REPLY,ifconfig 10.8.0.2 255.255.255.0,topology subnet,peer-id 0")

        assertEquals(emptyList(), push.dnsServers)
        assertEquals("AES-256-GCM", push.cipher)
        assertEquals(1500, push.tunMtu)
        assertEquals(10, push.pingSeconds)
        assertFalse(push.redirectGateway)
    }

    @Test
    fun `a reply without an address or a peer id is rejected`() {
        assertFailsWith<OpenVpnException> { parsePushReply("PUSH_REPLY,peer-id 1") }
        assertFailsWith<OpenVpnException> { parsePushReply("PUSH_REPLY,ifconfig 10.8.0.2 255.255.255.0") }
    }
}
