package org.uci.proxy.core.model

import kotlinx.serialization.Serializable

/**
 * Un servicio que da nombre a una IP pública. El Squid de la UCI rechaza los `CONNECT` a una IP escrita tal cual, pero
 * acepta el mismo destino por nombre; estos servicios (sslip.io, nip.io…) responden con esa IP a cualquier nombre que
 * la lleve dentro, sin registrar nada.
 */
@Serializable
data class IpNameService(
    val id: String,
    val name: String,
    /** El nombre con `{ip}` (149.22.82.28) o `{ip-}` (149-22-82-28) donde va la IP. */
    val template: String,
    val enabled: Boolean = true,
    /** De fábrica: no se puede borrar, solo desactivar. */
    val builtIn: Boolean = false,
) {
    /** El nombre que resuelve a [ip]. */
    fun nameFor(ip: String): String = template.replace(DASHED, ip.replace('.', '-')).replace(PLAIN, ip)

    companion object {
        const val PLAIN = "{ip}"
        const val DASHED = "{ip-}"

        val SSLIP = IpNameService(id = "sslip", name = "sslip.io", template = "{ip-}.sslip.io", builtIn = true)
        val NIP = IpNameService(id = "nip", name = "nip.io", template = "{ip}.nip.io", builtIn = true)

        /** Los de fábrica, en su orden. */
        val BUILT_IN = listOf(SSLIP, NIP)
    }
}

/**
 * Los ajustes que elige el usuario. Viajan al motor al conectar (en escritorio corre en otro proceso), así que un
 * cambio se aplica a partir de la siguiente conexión.
 */
@Serializable
data class AppSettings(
    /**
     * El túnel contesta por su cuenta las comprobaciones de conectividad de Android, Windows y macOS, que el proxy suele
     * bloquear y hacen que el sistema muestre «sin internet» aunque lo haya. Solo contesta si el proxy funciona.
     */
    val answerConnectivityChecks: Boolean = true,
    /**
     * Los nombres de la red local (los de sus dominios de búsqueda, como `uci.cu`) se resuelven con el DNS de la red y,
     * si su dirección es interna, van directos, sin proxy ni Otro País: el proxy institucional no suele abrirlos.
     */
    val intranetDirect: Boolean = true,
    /**
     * Mientras Otro País está caído (o no llega a conectar), lo que iba por él sale por el proxy. Apagado, se bloquea hasta
     * que vuelva, para quien prefiere no salir nunca por el proxy con lo que manda fuera.
     */
    val vpnFallbackToProxy: Boolean = true,
    /** Pedir al proxy las IP públicas por un nombre de [nameServices], en vez de por la IP tal cual. */
    val nameByIp: Boolean = true,
    /** Si ningún nombre sirve, probar por último la IP tal cual. */
    val tryRawIp: Boolean = true,
    /** En el orden en que se prueban; los desactivados se saltan. */
    val nameServices: List<IpNameService> = IpNameService.BUILT_IN,
    /** La cuota de la UCI: avisos, consulta y pausa. */
    val quota: QuotaSettings = QuotaSettings(),
)

/** Cómo se sigue la cuota de navegación de la UCI. */
@Serializable
data class QuotaSettings(
    /** Los porcentajes de la cuota a los que se avisa. Cada uno avisa una vez, hasta que el consumo vuelve a bajar. */
    val alerts: List<Int> = ALERT_THRESHOLDS,
    /** Cada cuántos minutos se consulta mientras el túnel está conectado. */
    val checkMinutes: Int = 5,
    /** Desconectar el túnel al llegar al [PAUSE_THRESHOLD] % de la cuota, para no agotarla sin querer. */
    val pauseAtLimit: Boolean = false,
    /**
     * Verificar el certificado del servicio. Apagado, como en UCIProxy: el de `cuotas.uci.cu` lo firma una autoridad de
     * la UCI («ROOT AC UCI», bajo la raíz del Estado) que ningún sistema trae, y con la verificación la consulta falla.
     */
    val verifyCertificate: Boolean = false,
    /** Otra dirección para el servicio, solo en modo desarrollo (para el simulador `tools/test_cuotas.py`). */
    val serviceUrl: String? = null,
) {
    companion object {
        val ALERT_THRESHOLDS = listOf(50, 75, 90)
        val CHECK_INTERVALS = listOf(5, 15, 30)
        const val PAUSE_THRESHOLD = 90
    }
}
