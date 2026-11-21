package org.uci.proxy.quota

/*
 * El servicio de cuotas de la UCI, tal como lo usa UCIProxy 5.0: SOAP 1.1 sobre HTTPS, en la intranet.
 *
 *   POST https://cuotas.uci.cu/servicios/v1/InetCuotasWS.php
 *   SOAPAction: "https://cuotas.uci.cu/servicios/v1/InetCuotasWS"
 *   <ObtenerCuota xmlns="https://cuotas.uci.cu/"> usuario, clave, dominio=uci.cu
 *
 * La respuesta trae, por este orden, la cuota asignada (MB, entero), la usada (MB, decimal) y el nivel de navegación.
 * UCIProxy los lee por posición, sin mirar sus nombres, y aquí igual: así no importa cómo se llamen.
 * Para probar sin la red de la UCI: tools/test_cuotas.py.
 */
internal object QuotaSoap {
    const val SERVICE_URL = "https://cuotas.uci.cu/servicios/v1/InetCuotasWS.php"
    const val SOAP_ACTION = "https://cuotas.uci.cu/servicios/v1/InetCuotasWS"
    const val NAMESPACE = "https://cuotas.uci.cu/"
    const val METHOD = "ObtenerCuota"
    const val DOMAIN = "uci.cu"

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
        // <ObtenerCuotaResponse><resultado><cuota/><usada/><nivel/></resultado></ObtenerCuotaResponse>
        val result = requireNotNull(first.children.firstOrNull()) { "respuesta sin resultado" }
        val values = result.children.map { it.text.trim() }
        require(values.size >= 3) { "se esperaban 3 valores y llegaron ${values.size}" }
        val assigned = requireNotNull(values[0].toDoubleOrNull()) { "cuota «${values[0]}» no es un número" }
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
