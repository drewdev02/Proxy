package org.uci.proxy.core.auth

internal fun ByteArray.readIntLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.readShortLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.readLongLe(offset: Int): Long =
    (readIntLe(offset).toLong() and 0xFFFFFFFFL) or (readIntLe(offset + 4).toLong() shl 32)

internal fun ByteArray.writeIntLe(offset: Int, value: Int) {
    for (i in 0 until 4) this[offset + i] = (value ushr (8 * i)).toByte()
}

internal fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.writeLongLe(offset: Int, value: Long) {
    for (i in 0 until 8) this[offset + i] = (value ushr (8 * i)).toByte()
}

/** UTF-16LE, la codificación "Unicode" de NTLM. Los `Char` de Kotlin ya son unidades UTF-16. */
internal fun String.encodeToUtf16Le(): ByteArray {
    val out = ByteArray(length * 2)
    forEachIndexed { index, char -> out.writeShortLe(index * 2, char.code) }
    return out
}

internal fun ByteArray.decodeUtf16Le(offset: Int, byteLength: Int): String =
    CharArray(byteLength / 2) { readShortLe(offset + it * 2).toChar() }.concatToString()
