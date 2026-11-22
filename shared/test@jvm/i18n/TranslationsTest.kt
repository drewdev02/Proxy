package org.uci.proxy.i18n

import org.uci.proxy.ui.Snapshots
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cada idioma tiene los mismos textos que el español (la carpeta `values`), ninguno vacío y con los mismos huecos
 * (`%1$s`, `%2$d`…). Así, una clave nueva sin traducir rompe el CI en vez de salir en español en mitad de otra lengua.
 *
 * Revisa los recursos de Compose de `shared` y los de Android de `androidApp` (notificaciones).
 */
class TranslationsTest {
    private val root = Snapshots.findModuleDir().parentFile

    @Test
    fun `los recursos de Compose tienen las mismas claves en todos los idiomas`() =
        check(File(root, "shared/composeResources"))

    @Test
    fun `los recursos de Android tienen las mismas claves en todos los idiomas`() =
        check(File(root, "androidApp/res"))

    private fun check(resources: File) {
        val base = texts(File(resources, "values"))
        assertTrue(base.isNotEmpty(), "No hay textos en ${resources}/values")
        val languages = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
        assertTrue(languages.isNotEmpty(), "No hay ningún idioma además del español en $resources")
        val problems = mutableListOf<String>()
        for (dir in languages.sortedBy { it.name }) {
            val translated = texts(dir)
            (base.keys - translated.keys).sorted().forEach { problems += "${dir.name}: falta «$it»" }
            (translated.keys - base.keys).sorted().forEach { problems += "${dir.name}: «$it» no existe en values" }
            for ((key, text) in translated) {
                val original = base[key] ?: continue
                if (text.isBlank()) problems += "${dir.name}: «$key» está vacío"
                if (placeholders(text) != placeholders(original)) {
                    problems += "${dir.name}: «$key» tiene huecos ${placeholders(text)} y en values ${placeholders(original)}"
                }
            }
        }
        base.filterValues { it.isBlank() }.keys.forEach { problems += "values: «$it» está vacío" }
        if (problems.isNotEmpty()) fail("Traducciones incompletas en $resources:\n" + problems.joinToString("\n"))
    }

    /** Los textos de una carpeta `values*`: cada `string`, cada variante de `plurals` y cada elemento de `string-array`. */
    private fun texts(dir: File): Map<String, String> = buildMap {
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = doc.documentElement.childNodes
            for (i in 0 until elements.length) {
                val element = elements.item(i) as? Element ?: continue
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> put(name, element.textContent)
                    // Cada idioma tiene sus propias variantes (one, few, many…): se compara solo la de «other».
                    "plurals" -> put("$name[other]", element.children("item").firstOrNull { it.getAttribute("quantity") == "other" }?.textContent.orEmpty())
                    "string-array" -> element.children("item").forEachIndexed { index, item -> put("$name[$index]", item.textContent) }
                }
            }
        }
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    private fun placeholders(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.value }.toSet()

    private companion object {
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
