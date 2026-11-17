package org.uci.proxy.core.auth

/*
 * MD4, MD5 y HMAC-MD5 en Kotlin puro. NTLMv2 los exige y no existen en la biblioteca común de Kotlin
 * (MD4 ni siquiera en el JDK). Son algoritmos rotos para uso general: aquí solo sirven para NTLM.
 */

private const val BLOCK_SIZE = 64

/** RFC 1320. */
internal fun md4(input: ByteArray): ByteArray {
    var a = 0x67452301
    var b = 0xefcdab89.toInt()
    var c = 0x98badcfe.toInt()
    var d = 0x10325476
    val x = IntArray(16)

    forEachBlock(input, x) {
        val aa = a
        val bb = b
        val cc = c
        val dd = d

        for (i in 0 until 16 step 4) {
            a = (a + ((b and c) or (b.inv() and d)) + x[i]).rotateLeft(3)
            d = (d + ((a and b) or (a.inv() and c)) + x[i + 1]).rotateLeft(7)
            c = (c + ((d and a) or (d.inv() and b)) + x[i + 2]).rotateLeft(11)
            b = (b + ((c and d) or (c.inv() and a)) + x[i + 3]).rotateLeft(19)
        }
        for (i in 0 until 4) {
            a = (a + majority(b, c, d) + x[i] + MD4_ROUND2).rotateLeft(3)
            d = (d + majority(a, b, c) + x[i + 4] + MD4_ROUND2).rotateLeft(5)
            c = (c + majority(d, a, b) + x[i + 8] + MD4_ROUND2).rotateLeft(9)
            b = (b + majority(c, d, a) + x[i + 12] + MD4_ROUND2).rotateLeft(13)
        }
        for (i in MD4_ROUND3_ORDER) {
            a = (a + (b xor c xor d) + x[i] + MD4_ROUND3).rotateLeft(3)
            d = (d + (a xor b xor c) + x[i + 8] + MD4_ROUND3).rotateLeft(9)
            c = (c + (d xor a xor b) + x[i + 4] + MD4_ROUND3).rotateLeft(11)
            b = (b + (c xor d xor a) + x[i + 12] + MD4_ROUND3).rotateLeft(15)
        }

        a += aa
        b += bb
        c += cc
        d += dd
    }
    return littleEndianBytes(a, b, c, d)
}

/** RFC 1321. */
internal fun md5(input: ByteArray): ByteArray {
    var a0 = 0x67452301
    var b0 = 0xefcdab89.toInt()
    var c0 = 0x98badcfe.toInt()
    var d0 = 0x10325476
    val m = IntArray(16)

    forEachBlock(input, m) {
        var a = a0
        var b = b0
        var c = c0
        var d = d0

        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> {
                    f = (b and c) or (b.inv() and d)
                    g = i
                }
                1 -> {
                    f = (d and b) or (d.inv() and c)
                    g = (5 * i + 1) % 16
                }
                2 -> {
                    f = b xor c xor d
                    g = (3 * i + 5) % 16
                }
                else -> {
                    f = c xor (b or d.inv())
                    g = (7 * i) % 16
                }
            }
            val rotated = (a + f + MD5_K[i] + m[g]).rotateLeft(MD5_SHIFTS[i])
            a = d
            d = c
            c = b
            b += rotated
        }

        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }
    return littleEndianBytes(a0, b0, c0, d0)
}

/** RFC 2104 con MD5. */
internal fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
    val blockKey = (if (key.size > BLOCK_SIZE) md5(key) else key).copyOf(BLOCK_SIZE)
    val inner = ByteArray(BLOCK_SIZE) { (blockKey[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(BLOCK_SIZE) { (blockKey[it].toInt() xor 0x5c).toByte() }
    return md5(outer + md5(inner + data))
}

private const val MD4_ROUND2 = 0x5a827999
private const val MD4_ROUND3 = 0x6ed9eba1

// En la tercera ronda de MD4 las palabras se recorren en orden de bits invertidos.
private val MD4_ROUND3_ORDER = intArrayOf(0, 2, 1, 3)

private fun majority(x: Int, y: Int, z: Int): Int = (x and y) or (x and z) or (y and z)

private val MD5_SHIFTS = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

private val MD5_K = longArrayOf(
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
).map { it.toInt() }.toIntArray()

/**
 * Aplica el relleno común a MD4 y MD5 (bit 1, ceros y longitud en bits de 64 bits, little-endian)
 * y entrega cada bloque de 64 bytes ya convertido a 16 palabras en [words].
 */
private inline fun forEachBlock(input: ByteArray, words: IntArray, block: () -> Unit) {
    val paddedSize = ((input.size + 8) / BLOCK_SIZE + 1) * BLOCK_SIZE
    val padded = input.copyOf(paddedSize)
    padded[input.size] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8
    for (i in 0 until 8) padded[paddedSize - 8 + i] = (bitLength ushr (8 * i)).toByte()

    for (offset in 0 until paddedSize step BLOCK_SIZE) {
        for (i in 0 until 16) words[i] = padded.readIntLe(offset + 4 * i)
        block()
    }
}

private fun littleEndianBytes(vararg words: Int): ByteArray {
    val out = ByteArray(words.size * 4)
    words.forEachIndexed { index, word -> out.writeIntLe(index * 4, word) }
    return out
}
