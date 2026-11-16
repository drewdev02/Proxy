package org.uci.proxy.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

class DigestsTest {
    private fun ascii(text: String) = text.encodeToByteArray()

    // Vectores de la RFC 1320, apéndice A.5.
    @Test
    fun `md4 matches the RFC 1320 vectors`() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", md4(ascii("")).toHex())
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", md4(ascii("a")).toHex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", md4(ascii("abc")).toHex())
        assertEquals("d9130a8164549fe818874806e1c7014b", md4(ascii("message digest")).toHex())
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            md4(ascii("12345678901234567890123456789012345678901234567890123456789012345678901234567890")).toHex(),
        )
    }

    // Vectores de la RFC 1321, apéndice A.5.
    @Test
    fun `md5 matches the RFC 1321 vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5(ascii("")).toHex())
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5(ascii("a")).toHex())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5(ascii("abc")).toHex())
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", md5(ascii("message digest")).toHex())
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            md5(ascii("12345678901234567890123456789012345678901234567890123456789012345678901234567890")).toHex(),
        )
    }

    @Test
    fun `digests handle inputs around the block boundary`() {
        // 55, 56 y 64 bytes son los tamaños donde el relleno pasa de uno a dos bloques.
        assertEquals("ef1772b6dff9a122358552954ad0df65", md5(ByteArray(55) { 'a'.code.toByte() }).toHex())
        assertEquals("3b0c8ac703f828b04c6c197006d17218", md5(ByteArray(56) { 'a'.code.toByte() }).toHex())
        assertEquals("014842d480b571495a4a0363793f7367", md5(ByteArray(64) { 'a'.code.toByte() }).toHex())
    }

    // Vectores de la RFC 2202, sección 2.
    @Test
    fun `hmacMd5 matches the RFC 2202 vectors`() {
        assertEquals(
            "9294727a3638bb1c13f48ef8158bfc9d",
            hmacMd5(ByteArray(16) { 0x0b }, ascii("Hi There")).toHex(),
        )
        assertEquals(
            "750c783e6ab0b503eaa86e310a5db738",
            hmacMd5(ascii("Jefe"), ascii("what do ya want for nothing?")).toHex(),
        )
        // Clave más larga que el bloque: debe pasar antes por MD5.
        assertEquals(
            "6b1ab7fe4bd7bf8f0b62e6ce61b9d0cd",
            hmacMd5(
                ByteArray(80) { 0xaa.toByte() },
                ascii("Test Using Larger Than Block-Size Key - Hash Key First"),
            ).toHex(),
        )
    }
}
