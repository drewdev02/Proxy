package org.uci.proxy.core.vpn

/** Un servidor al que conectar. */
data class OvpnRemote(val host: String, val port: Int)

/**
 * Lo que ProxyUCI entiende de un archivo `.ovpn`. El cliente solo implementa un subconjunto de OpenVPN: TCP, cifrado
 * AEAD y `tls-crypt`, que es lo que ofrecen los servicios actuales. Lo demás se rechaza al leer, con un mensaje claro,
 * en vez de fallar a medio handshake.
 */
class OvpnConfig(
    val remotes: List<OvpnRemote>,
    /** Certificado de la autoridad que firma al servidor, en formato PEM. */
    val caPem: String,
    /** La clave estática de `tls-crypt` (256 bytes). */
    val tlsCryptKey: ByteArray,
    /** Si el servidor pide usuario y contraseña. */
    val needsCredentials: Boolean,
    /** El cifrado de datos que pide el archivo (`cipher`); el servidor puede imponer otro al empujar su configuración. */
    val cipher: String?,
    val remoteRandom: Boolean,
    val tunMtu: Int?,
)

class OvpnConfigException(message: String) : Exception(message)

/** Lee el texto de un `.ovpn`. @throws OvpnConfigException si no sirve para este cliente. */
fun parseOvpn(text: String): OvpnConfig {
    val blocks = mutableMapOf<String, String>()
    val directives = mutableListOf<List<String>>()

    var openBlock: String? = null
    val blockLines = mutableListOf<String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        val current = openBlock
        if (current != null) {
            if (line == "</$current>") {
                blocks[current] = blockLines.joinToString("\n")
                blockLines.clear()
                openBlock = null
            } else {
                blockLines += line
            }
            continue
        }
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
            openBlock = line.substring(1, line.length - 1)
            continue
        }
        directives += line.split(Regex("\\s+"))
    }
    if (openBlock != null) throw OvpnConfigException("El archivo está incompleto: falta cerrar <$openBlock>.")

    fun values(name: String) = directives.filter { it[0] == name }
    fun has(name: String) = directives.any { it[0] == name }

    if (!has("client") && !has("tls-client")) throw OvpnConfigException("El archivo no es un perfil de cliente OpenVPN.")
    if (values("dev").lastOrNull()?.getOrNull(1)?.startsWith("tun") == false) {
        throw OvpnConfigException("Solo se admiten perfiles de tipo tun, no tap.")
    }

    val defaultProto = values("proto").lastOrNull()?.getOrNull(1) ?: "udp"
    val remotes = values("remote").mapNotNull { parts ->
        val host = parts.getOrNull(1) ?: return@mapNotNull null
        val port = parts.getOrNull(2)?.toIntOrNull() ?: 1194
        val proto = parts.getOrNull(3) ?: defaultProto
        if (proto.startsWith("tcp")) OvpnRemote(host, port) else null
    }
    if (remotes.isEmpty()) {
        throw OvpnConfigException(
            "El perfil no tiene servidores TCP. A través de un proxy solo funciona OpenVPN sobre TCP: descarga la variante TCP del perfil.",
        )
    }

    if (has("tls-auth")) throw OvpnConfigException("El perfil usa tls-auth, que todavía no se admite (sí tls-crypt).")
    if (has("tls-crypt-v2")) throw OvpnConfigException("El perfil usa tls-crypt-v2, que todavía no se admite (sí tls-crypt).")
    if (blocks.containsKey("cert") || blocks.containsKey("key") || has("cert") || has("pkcs12")) {
        throw OvpnConfigException("El perfil usa certificado de cliente, que todavía no se admite. Solo usuario y contraseña.")
    }
    values("compress").firstOrNull { it.getOrNull(1) != null && it[1] != "stub-v2" }?.let {
        throw OvpnConfigException("El perfil usa compresión (${it[1]}), que no se admite.")
    }
    if (has("comp-lzo") && values("comp-lzo").any { it.getOrNull(1) != "no" }) {
        throw OvpnConfigException("El perfil usa compresión LZO, que no se admite.")
    }

    val ca = blocks["ca"]?.takeIf { it.contains("BEGIN CERTIFICATE") }
        ?: throw OvpnConfigException("El perfil no incluye el certificado de la autoridad (<ca>).")
    val tlsCrypt = blocks["tls-crypt"] ?: throw OvpnConfigException("El perfil no incluye <tls-crypt>, que es lo único que se admite.")
    val key = parseStaticKey(tlsCrypt)

    return OvpnConfig(
        remotes = remotes,
        caPem = ca,
        tlsCryptKey = key,
        needsCredentials = has("auth-user-pass"),
        cipher = values("cipher").lastOrNull()?.getOrNull(1),
        remoteRandom = has("remote-random"),
        tunMtu = values("tun-mtu").lastOrNull()?.getOrNull(1)?.toIntOrNull(),
    )
}

private const val STATIC_KEY_BYTES = 256

/** El bloque `-----BEGIN OpenVPN Static key V1-----` con 256 bytes en hexadecimal. */
private fun parseStaticKey(block: String): ByteArray {
    val hex = block.lines().filter { !it.startsWith("-----") && !it.startsWith("#") && it.isNotBlank() }.joinToString("") { it.trim() }
    if (hex.length != STATIC_KEY_BYTES * 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        throw OvpnConfigException("La clave de <tls-crypt> no tiene el formato esperado.")
    }
    return ByteArray(STATIC_KEY_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
