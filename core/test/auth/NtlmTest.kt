package org.uci.proxy.core.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Valores de MS-NLMP sección 4.2.4 (NTLMv2 Authentication): usuario "User", dominio "Domain", clave "Password". */
class NtlmTest {
    private val serverChallenge = "0123456789abcdef".hexToBytes()
    private val clientChallenge = "aaaaaaaaaaaaaaaa".hexToBytes()

    // Pares AV: MsvAvNbDomainName="Domain", MsvAvNbComputerName="Server", MsvAvEOL.
    private val targetInfo = (
        "02000c0044006f006d00610069006e00" +
            "01000c00530065007200760065007200" +
            "00000000"
        ).hexToBytes()

    private val responseKey = NtlmMessages.ntlmV2ResponseKey("User", "Domain", "Password")

    @Test
    fun `NT hash of the password matches the specification`() {
        assertEquals("a4f49c406510bdcab6824ee7c30fd852", md4("Password".encodeToUtf16Le()).toHex())
    }

    @Test
    fun `NTLMv2 response key matches the specification`() {
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", responseKey.toHex())
    }

    @Test
    fun `the user name is upper-cased but the domain is not`() {
        assertContentEquals(responseKey, NtlmMessages.ntlmV2ResponseKey("user", "Domain", "Password"))
        assertEquals(false, responseKey.contentEquals(NtlmMessages.ntlmV2ResponseKey("User", "DOMAIN", "Password")))
    }

    @Test
    fun `NTProofStr matches the specification`() {
        val response = NtlmMessages.ntlmV2Response(responseKey, serverChallenge, clientChallenge, fileTime = 0, targetInfo)

        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", response.copyOf(16).toHex())
    }

    @Test
    fun `the blob after the proof echoes time client challenge and target info`() {
        val response = NtlmMessages.ntlmV2Response(responseKey, serverChallenge, clientChallenge, fileTime = 0x0102030405060708, targetInfo)
        val blob = response.copyOfRange(16, response.size)

        assertEquals("0101000000000000", blob.copyOf(8).toHex())
        assertEquals("0807060504030201", blob.copyOfRange(8, 16).toHex())
        assertContentEquals(clientChallenge, blob.copyOfRange(16, 24))
        assertContentEquals(targetInfo, blob.copyOfRange(28, 28 + targetInfo.size))
        assertEquals(28 + targetInfo.size + 4, blob.size)
    }

    @Test
    fun `LMv2 response matches the specification`() {
        val response = NtlmMessages.lmV2Response(responseKey, serverChallenge, clientChallenge)

        assertEquals("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", response.toHex())
    }

    @Test
    fun `type 1 is a 32 byte negotiate message`() {
        val message = NtlmMessages.type1()

        assertEquals(32, message.size)
        assertEquals("4e544c4d53535000" + "01000000", message.copyOf(12).toHex())
    }

    private fun type2(targetName: String = "Domain", info: ByteArray = targetInfo): ByteArray {
        val name = targetName.encodeToUtf16Le()
        val message = ByteArray(48 + name.size + info.size)
        "4e544c4d53535000".hexToBytes().copyInto(message)
        message.writeIntLe(8, 2)
        message.writeShortLe(12, name.size)
        message.writeShortLe(14, name.size)
        message.writeIntLe(16, 48)
        message.writeIntLe(20, 0x00800001) // UNICODE + TARGET_INFO
        serverChallenge.copyInto(message, 24)
        message.writeShortLe(40, info.size)
        message.writeShortLe(42, info.size)
        message.writeIntLe(44, 48 + name.size)
        name.copyInto(message, 48)
        info.copyInto(message, 48 + name.size)
        return message
    }

    @Test
    fun `type 2 parsing extracts challenge target name and target info`() {
        val challenge = NtlmMessages.parseType2(type2())

        assertContentEquals(serverChallenge, challenge.serverChallenge)
        assertEquals("Domain", challenge.targetName)
        assertContentEquals(targetInfo, challenge.targetInfo)
        assertNull(challenge.serverTimestamp)
    }

    @Test
    fun `type 2 parsing finds the server timestamp`() {
        // MsvAvTimestamp (id 7, 8 bytes) delante del terminador.
        val withTimestamp = "070008000807060504030201".hexToBytes() + "00000000".hexToBytes()

        val challenge = NtlmMessages.parseType2(type2(info = withTimestamp))

        assertEquals(0x0102030405060708, challenge.serverTimestamp)
    }

    @Test
    fun `garbage is rejected as type 2`() {
        assertFailsWith<NtlmFormatException> { NtlmMessages.parseType2(ByteArray(10)) }
        assertFailsWith<NtlmFormatException> { NtlmMessages.parseType2(NtlmMessages.type1()) }
    }

    private fun ByteArray.field(headerOffset: Int): ByteArray {
        val length = readShortLe(headerOffset)
        val start = readIntLe(headerOffset + 4)
        return copyOfRange(start, start + length)
    }

    @Test
    fun `type 3 carries the specification responses and the identity fields`() {
        val message = NtlmMessages.type3(
            challenge = NtlmMessages.parseType2(type2()),
            user = NtlmUser("User", "Domain"),
            password = "Password",
            clientChallenge = clientChallenge,
            clientFileTime = 0,
            workstation = "COMPUTER",
        )

        assertEquals("4e544c4d53535000" + "03000000", message.copyOf(12).toHex())
        assertEquals("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", message.field(12).toHex())
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", message.field(20).copyOf(16).toHex())
        assertEquals("Domain", message.field(28).let { it.decodeUtf16Le(0, it.size) })
        assertEquals("User", message.field(36).let { it.decodeUtf16Le(0, it.size) })
        assertEquals("COMPUTER", message.field(44).let { it.decodeUtf16Le(0, it.size) })
    }

    @Test
    fun `without an explicit domain the server target name is used`() {
        val message = NtlmMessages.type3(
            challenge = NtlmMessages.parseType2(type2(targetName = "UCI")),
            user = NtlmUser("andrey", domain = null),
            password = "x",
            clientChallenge = clientChallenge,
            clientFileTime = 0,
            workstation = "W",
        )

        assertEquals("UCI", message.field(28).let { it.decodeUtf16Le(0, it.size) })
    }

    @Test
    fun `with a server timestamp the LM response is zeroed and that time is used`() {
        val info = "070008000807060504030201".hexToBytes() + "00000000".hexToBytes()

        val message = NtlmMessages.type3(
            challenge = NtlmMessages.parseType2(type2(info = info)),
            user = NtlmUser("User", "Domain"),
            password = "Password",
            clientChallenge = clientChallenge,
            clientFileTime = 0x7777777777777777,
            workstation = "W",
        )

        assertContentEquals(ByteArray(24), message.field(12))
        // La hora va en el blob, 8 bytes después del NTProofStr (16) y la cabecera del blob (8).
        assertEquals("0807060504030201", message.field(20).copyOfRange(24, 32).toHex())
    }

    @Test
    fun `user names are split from their domain`() {
        assertEquals(NtlmUser("andrey", "UCI"), NtlmUser.parse("UCI\\andrey"))
        assertEquals(NtlmUser("andrey", "uci.cu"), NtlmUser.parse("andrey@uci.cu"))
        assertEquals(NtlmUser("andrey", null), NtlmUser.parse("andrey"))
    }
}
