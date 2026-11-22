package org.uci.proxy.core.port

import kotlin.test.Test
import kotlin.test.assertEquals

// Con el mismo nombre que las de java.net: la clasificación mira el nombre de la clase, igual en todas las plataformas.
private class UnknownHostException(message: String) : Exception(message)
private class ConnectException(message: String) : Exception(message)
private class SocketTimeoutException(message: String) : Exception(message)
private class NoRouteToHostException(message: String) : Exception(message)

class NetFailureTest {
    @Test
    fun `las excepciones de java net se reconocen por su clase`() {
        assertEquals(NetFailure.NAME_NOT_FOUND, classifyNetworkError(UnknownHostException("proxy.uci.cu")))
        assertEquals(NetFailure.REFUSED, classifyNetworkError(ConnectException("Connection refused")))
        assertEquals(NetFailure.TIMEOUT, classifyNetworkError(SocketTimeoutException("connect timed out")))
        assertEquals(NetFailure.NO_ROUTE, classifyNetworkError(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `los errores POSIX de iOS se reconocen por su mensaje`() {
        assertEquals(NetFailure.REFUSED, classifyNetworkError(Exception("connect failed: ECONNREFUSED (61)")))
        assertEquals(NetFailure.NO_NETWORK, classifyNetworkError(Exception("Network is unreachable")))
        assertEquals(NetFailure.NAME_NOT_FOUND, classifyNetworkError(Exception("nodename nor servname provided, or not known")))
        assertEquals(NetFailure.RESET, classifyNetworkError(Exception("Connection reset by peer")))
    }

    @Test
    fun `se busca en las causas y se respeta una TcpException ya clasificada`() {
        assertEquals(NetFailure.TIMEOUT, classifyNetworkError(IllegalStateException("envoltorio", SocketTimeoutException("x"))))
        assertEquals(NetFailure.CLOSED, classifyNetworkError(Exception("envoltorio", TcpException(NetFailure.CLOSED, "cerrada"))))
    }

    @Test
    fun `lo que no se reconoce queda como OTHER`() {
        assertEquals(NetFailure.OTHER, classifyNetworkError(IllegalStateException("algo raro")))
    }
}
