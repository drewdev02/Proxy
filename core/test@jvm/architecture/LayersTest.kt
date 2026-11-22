package org.uci.proxy.core.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Las capas de `core/src` (el código común) y qué puede importar cada una. Las dependencias van solo hacia abajo:
 *
 * - `model`, `common`, `packet`, `auth`: no dependen de nada del proyecto. Datos y utilidades puras.
 * - `port`: las interfaces hacia fuera (almacenes, red, túnel, avisos). Solo conocen el modelo.
 * - `data`: las implementaciones que guardan en los almacenes de los puertos.
 * - Protocolos (`routing`, `dns`, `tcp`, `net`, `proxy`, `vpn`): hablan con la red; entre ellos se usan libremente.
 * - Funciones (`log`, `profile`, `settings`, `traffic`, `quota`): la lógica de cada parte de la app; usan los puertos,
 *   nunca las implementaciones de `data`.
 * - `engine`: monta el túnel con todo lo anterior salvo `data`.
 * - `di`: lo conecta todo.
 *
 * Un paquete nuevo tiene que añadirse aquí: así se decide a qué capa pertenece.
 */
class LayersTest {
    private val base = setOf("model", "common", "packet", "auth")
    private val protocols = setOf("routing", "dns", "tcp", "net", "proxy", "vpn")
    private val features = setOf("log", "profile", "settings", "traffic", "quota")

    private val allowed: Map<String, Set<String>> = buildMap {
        base.forEach { put(it, emptySet()) }
        put("port", setOf("model", "packet", "common"))
        put("data", setOf("model", "port", "common"))
        protocols.forEach { put(it, base + "port" + protocols) }
        features.forEach { put(it, base + "port" + protocols + features) }
        put("engine", base + "port" + protocols + features + "engine")
        put("di", base + "port" + "data" + protocols + features + "engine")
    }

    private val sources: File = sequenceOf("src", "core/src", "../core/src")
        .map { File(it).absoluteFile }
        .firstOrNull { File(it, "model").isDirectory && File(it, "port").isDirectory }
        ?: error("No se encontró core/src desde ${File("").absolutePath}")

    private val import = Regex("""^import org\.uci\.proxy\.core\.(\w+)\.""", RegexOption.MULTILINE)

    @Test
    fun `every package in core belongs to a layer`() {
        val packages = sources.listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()

        assertEquals(emptySet(), packages - allowed.keys, "Paquetes sin capa: añádelos a LayersTest")
    }

    @Test
    fun `each layer only imports from the layers below it`() {
        val violations = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val layer = file.relativeTo(sources).path.substringBefore(File.separator)
            val ok = allowed[layer] ?: return@flatMap emptySequence()
            import.findAll(file.readText()).map { it.groupValues[1] }
                .filter { it != layer && it !in ok }
                .map { "${file.relativeTo(sources)} importa $it" }
        }.toList()

        if (violations.isNotEmpty()) fail("Dependencias contra las capas:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `the test really reads the sources`() {
        assertTrue(sources.walkTopDown().count { it.extension == "kt" } > 50)
    }
}
