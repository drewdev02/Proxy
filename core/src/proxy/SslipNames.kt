package org.uci.proxy.core.proxy

import org.uci.proxy.core.packet.NON_PUBLIC_RANGES
import org.uci.proxy.core.packet.parseIpv4

/**
 * El Squid de la UCI rechaza (403) los `CONNECT` a una IP pública escrita tal cual, pero acepta el mismo destino por
 * nombre. sslip.io ofrece nombres que resuelven a la IP que llevan dentro (`149-22-82-28.sslip.io` es 149.22.82.28),
 * así que el proxy puede resolverlos él mismo. SuperProxy hace lo mismo con toda IP pública, siempre.
 *
 * @return ese nombre si [host] es una IP pública; null si ya es un nombre o es una IP privada (que el proxy alcanza
 *   directamente y sslip.io no tiene por qué resolver bien).
 */
fun sslipNameFor(host: String): String? {
    val address = parseIpv4(host) ?: return null
    if (NON_PUBLIC_RANGES.any { it.contains(address) }) return null
    return host.replace('.', '-') + SSLIP_SUFFIX
}

private const val SSLIP_SUFFIX = ".sslip.io"
