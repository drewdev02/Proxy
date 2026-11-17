package org.uci.proxy.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.uci.proxy.core.port.HostResolver
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

class JavaHostResolver(private val ioDispatcher: CoroutineDispatcher) : HostResolver {
    // La resolución es bloqueante. Una dirección ya escrita con puntos se devuelve tal cual, sin consultar.
    override suspend fun resolveIpv4(host: String): String? = withContext(ioDispatcher) {
        try {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
        } catch (_: UnknownHostException) {
            null
        }
    }
}
