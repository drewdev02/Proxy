package org.uci.proxy.core.platform.macos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.platform.CommandRunner
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.port.LocalDnsSource

/**
 * El DNS del servicio de red principal (Wi-Fi, Ethernet…), leído de la configuración dinámica con `scutil`.
 *
 * Se lee el del servicio y no el global (`State:/Network/Global/DNS`), porque con el túnel activo el global es el del
 * túnel; así también sirve al reconectar.
 */
class MacLocalDnsSource(private val runner: CommandRunner) : LocalDnsSource {
    override suspend fun read(): LocalDnsConfig = withContext(Dispatchers.IO) { readNow() }

    private fun readNow(): LocalDnsConfig {
        val service = ScutilOutput.value(show("State:/Network/Global/IPv4"), "PrimaryService")
            ?.takeIf { id -> id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' } }
            ?: return LocalDnsConfig.NONE
        // El de estado es el que está en uso (el que dio el DHCP); el de ajustes, el que fijó la persona a mano.
        val dictionaries = listOf(show("State:/Network/Service/$service/DNS"), show("Setup:/Network/Service/$service/DNS"))
        val servers = dictionaries.map { ScutilOutput.array(it, "ServerAddresses").filter { s -> parseIpv4(s) != null } }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
        val domains = dictionaries.flatMap { listOfNotNull(ScutilOutput.value(it, "DomainName")) + ScutilOutput.array(it, "SearchDomains") }
        return LocalDnsConfig.of(servers, domains)
    }

    private fun show(key: String): String = runner.run(listOf(SCUTIL), "show $key\nquit\n").output

    private companion object {
        const val SCUTIL = "/usr/sbin/scutil"
    }
}

/** Lee los diccionarios que imprime `scutil` con `show`: solo el primer nivel, que es lo que hace falta aquí. */
internal object ScutilOutput {
    /** El valor de una clave simple (`Clave : valor`), o null si no está. */
    fun value(text: String, key: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key :") && !it.endsWith("{") }
        ?.substringAfter(':')?.trim()

    /** Los elementos de una clave de tipo lista (`Clave : <array> { 0 : a  1 : b }`), o vacío si no está. */
    fun array(text: String, key: String): List<String> {
        val lines = text.lines().map { it.trim() }
        val start = lines.indexOfFirst { it.startsWith("$key :") && it.endsWith("{") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it != "}" }.mapNotNull { line ->
            line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
        }
    }
}

