package org.uci.proxy.core.vpn

import org.uci.proxy.core.model.VpnHandshakeIssue
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory

/** Crea sesiones TLS con `SSLEngine`, que trabaja sobre buffers y por eso sirve para llevar TLS dentro de OpenVPN. */
class SslEngineTlsSessionFactory : TlsSessionFactory {
    override fun create(caPem: String): TlsSession = SslEngineTlsSession(caPem)
}

/**
 * El servidor se valida contra la autoridad del perfil y debe presentar un certificado de servidor
 * (`remote-cert-tls server`). El nombre no se comprueba: OpenVPN conecta por dirección IP.
 */
class SslEngineTlsSession(caPem: String) : TlsSession {
    private val engine: SSLEngine = createEngine(caPem)

    private val outgoing = ArrayDeque<ByteArray>()
    private val applicationData = ArrayDeque<ByteArray>()
    private var pendingInput = ByteArray(0)
    private var established = false

    override val isEstablished: Boolean get() = established

    init {
        engine.beginHandshake()
        pump()
    }

    override fun receive(ciphertext: ByteArray) {
        pendingInput += ciphertext
        pump()
    }

    override fun readApplicationData(): ByteArray? = applicationData.removeFirstOrNull()

    override fun writeApplicationData(plaintext: ByteArray) {
        val input = ByteBuffer.wrap(plaintext)
        while (input.hasRemaining()) {
            val result = wrap(input)
            if (result.status == SSLEngineResult.Status.CLOSED) throw OpenVpnException(VpnHandshakeIssue.CLOSED, "La conexión TLS se cerró")
        }
    }

    override fun takeOutgoing(): ByteArray? = outgoing.removeFirstOrNull()

    /** Avanza el handshake y descifra todo lo recibido mientras se pueda. */
    private fun pump() {
        try {
            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0))
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> if (!unwrap()) return
                    else -> if (pendingInput.isEmpty() || !unwrap()) return
                }
            }
        } catch (e: SSLException) {
            // Si la cadena del servidor no lleva a la autoridad del archivo, no es el servidor que se espera.
            val untrusted = generateSequence<Throwable>(e) { it.cause }.any { it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException }
            val issue = if (untrusted) VpnHandshakeIssue.SERVER_CERTIFICATE else VpnHandshakeIssue.TLS_FAILED
            throw OpenVpnException(issue, "Falló el TLS con el servidor VPN: ${e.message}", e)
        }
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    private fun wrap(source: ByteBuffer): SSLEngineResult {
        val target = ByteBuffer.allocate(engine.session.packetBufferSize)
        val result = engine.wrap(source, target)
        noteHandshake(result)
        target.flip()
        if (target.hasRemaining()) outgoing += ByteArray(target.remaining()).also(target::get)
        return result
    }

    /** @return false si faltan bytes para completar un registro TLS. */
    private fun unwrap(): Boolean {
        if (pendingInput.isEmpty()) return false
        val input = ByteBuffer.wrap(pendingInput)
        val target = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val result = engine.unwrap(input, target)
        noteHandshake(result)
        pendingInput = pendingInput.copyOfRange(input.position(), pendingInput.size)
        target.flip()
        if (target.hasRemaining()) applicationData += ByteArray(target.remaining()).also(target::get)
        return result.status != SSLEngineResult.Status.BUFFER_UNDERFLOW &&
            (result.bytesConsumed() > 0 || result.bytesProduced() > 0 || engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
    }

    private fun noteHandshake(result: SSLEngineResult) {
        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) established = true
    }

    private companion object {
        fun createEngine(caPem: String): SSLEngine {
            val factory = CertificateFactory.getInstance("X.509")
            val authority = try {
                factory.generateCertificate(ByteArrayInputStream(caPem.encodeToByteArray()))
            } catch (e: java.security.cert.CertificateException) {
                throw InvalidCertificateException("El certificado de la autoridad del perfil no es válido", e)
            }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", authority)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val context = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            return context.createSSLEngine().apply {
                useClientMode = true
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            }
        }
    }
}
