package org.uci.proxy.quota

/*
 * El servicio de cuotas de la UCI (SOAP 1.1, rpc/encoded, hecho en PHP), en la intranet. Su WSDL, en
 * https://cuotas.uci.cu/servicios/v1/InetCuotasWS.php?wsdl:
 *
 *   ObtenerCuota(usuario, clave, dominio) → ObtenerCuotaReturn de tipo Usuario:
 *     cuota (float, MB), cuota_usada (MB), nivel_navegacion («Navegación plena»…), usuario
 *   namespace urn:InetCuotasWS, SOAPAction "urn:InetCuotasWSAction"
 *
 * El tipo se declara con xsd:all, que permite cualquier orden: los campos se leen por su nombre y, si no se reconocen,
 * por posición (como UCIProxy, que también funcionaba con otro namespace y otra acción).
 * Para probar sin la red de la UCI: tools/test_cuotas.py.
 */
internal object QuotaSoap {
    const val SERVICE_URL = "https://cuotas.uci.cu/servicios/v1/InetCuotasWS.php"
    const val SOAP_ACTION = "urn:InetCuotasWSAction"
    const val NAMESPACE = "urn:InetCuotasWS"
    const val METHOD = "ObtenerCuota"
    const val DOMAIN = "uci.cu"

    // Los nombres de los campos de la respuesta, según el WSDL.
    private const val ASSIGNED = "cuota"
    private const val USED = "cuota_usada"
    private const val LEVEL = "nivel_navegacion"

    fun request(user: String, password: String): String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<v:Envelope xmlns:v="http://schemas.xmlsoap.org/soap/envelope/" xmlns:i="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<v:Header/><v:Body>" +
            """<$METHOD xmlns="$NAMESPACE">""" +
            "<usuario>${escapeXml(user)}</usuario>" +
            "<clave>${escapeXml(password)}</clave>" +
            "<dominio>$DOMAIN</dominio>" +
            "</$METHOD>" +
            "</v:Body></v:Envelope>"

    /** Lo que dijo el servicio. */
    sealed interface Answer {
        data class Quota(val assignedMb: Long, val usedMb: Double, val level: String) : Answer

        /** Un fallo SOAP: lo devuelve con usuario o contraseña incorrectos. */
        data class Fault(val code: String, val message: String) : Answer
    }

    /** @throws IllegalArgumentException si [body] no es una respuesta que se entienda (el servicio cambió). */
    fun parse(body: String): Answer {
        val envelope = Xml.parse(body)
        val soapBody = requireNotNull(envelope.children.firstOrNull { it.name == "Body" }) { "sin Body" }
        val first = requireNotNull(soapBody.children.firstOrNull()) { "Body vacío" }
        if (first.name == "Fault") {
            return Answer.Fault(
                code = first.children.firstOrNull { it.name == "faultcode" }?.text.orEmpty(),
                message = first.children.firstOrNull { it.name == "faultstring" }?.text.orEmpty(),
            )
        }
        // <ObtenerCuotaResponse><ObtenerCuotaReturn><cuota/><cuota_usada/><nivel_navegacion/><usuario/>…
        val result = requireNotNull(first.children.firstOrNull()) { "respuesta sin resultado" }
        val byName = result.children.associate { it.name to it.text.trim() }
        val values = if (ASSIGNED in byName && USED in byName) {
            listOf(byName.getValue(ASSIGNED), byName.getValue(USED), byName[LEVEL].orEmpty())
        } else {
            result.children.map { it.text.trim() }.also { require(it.size >= 3) { "se esperaban 3 valores y llegaron ${it.size}" } }
        }
        val assigned = requireNotNull(values[0].replace(',', '.').toDoubleOrNull()) { "cuota «${values[0]}» no es un número" }
        val used = requireNotNull(values[1].replace(',', '.').toDoubleOrNull()) { "cuota usada «${values[1]}» no es un número" }
        return Answer.Quota(assigned.toLong(), used, values[2])
    }

    private fun escapeXml(text: String) = buildString {
        text.forEach {
            when (it) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(it)
            }
        }
    }
}

/**
 * Lector de XML mínimo, lo justo para una respuesta SOAP: elementos, atributos (que se ignoran), texto, CDATA y las
 * entidades de siempre. Los nombres quedan sin prefijo de namespace (`SOAP-ENV:Body` → `Body`).
 */
internal object Xml {
    class Element(val name: String) {
        val children = mutableListOf<Element>()
        private val content = StringBuilder()
        val text: String get() = content.toString()

        internal fun appendText(value: String) {
            content.append(value)
        }
    }

    fun parse(xml: String): Element {
        val root = Element("#document")
        val stack = ArrayDeque<Element>().apply { addLast(root) }
        var i = 0
        while (i < xml.length) {
            val open = xml.indexOf('<', i)
            if (open < 0) {
                stack.last().appendText(decode(xml.substring(i)))
                break
            }
            if (open > i) stack.last().appendText(decode(xml.substring(i, open)))
            when {
                xml.startsWith("<!--", open) -> i = xml.indexOf("-->", open).let { require(it >= 0) { "comentario sin cerrar" }; it + 3 }
                xml.startsWith("<![CDATA[", open) -> {
                    val end = xml.indexOf("]]>", open)
                    require(end >= 0) { "CDATA sin cerrar" }
                    stack.last().appendText(xml.substring(open + 9, end))
                    i = end + 3
                }
                xml.startsWith("<?", open) || xml.startsWith("<!", open) -> i = xml.indexOf('>', open).let { require(it >= 0) { "declaración sin cerrar" }; it + 1 }
                xml.startsWith("</", open) -> {
                    val end = xml.indexOf('>', open)
                    require(end >= 0) { "etiqueta sin cerrar" }
                    val name = localName(xml.substring(open + 2, end).trim())
                    require(stack.size > 1 && stack.last().name == name) { "cierre inesperado de $name" }
                    stack.removeLast()
                    i = end + 1
                }
                else -> {
                    val end = xml.indexOf('>', open)
                    require(end >= 0) { "etiqueta sin cerrar" }
                    val tag = xml.substring(open + 1, end)
                    val selfClosing = tag.endsWith("/")
                    val name = localName(tag.removeSuffix("/").trim().split(' ', '\t', '\n', '\r').first())
                    val element = Element(name)
                    stack.last().children += element
                    if (!selfClosing) stack.addLast(element)
                    i = end + 1
                }
            }
        }
        require(stack.size == 1) { "faltan cierres: ${stack.last().name}" }
        return requireNotNull(root.children.singleOrNull()) { "se esperaba un único elemento raíz" }
    }

    private fun localName(qualified: String) = qualified.substringAfter(':')

    private fun decode(text: String): String {
        if ('&' !in text) return text
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);").replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x") -> entity.drop(2).toInt(16).toChar().toString()
                entity.startsWith("#") -> entity.drop(1).toInt().toChar().toString()
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "amp" -> "&"
                entity == "quot" -> "\""
                else -> "'"
            }
        }
    }
}
