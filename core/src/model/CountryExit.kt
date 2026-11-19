package org.uci.proxy.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Los motores con los que un perfil puede salir por «Otro País». */
@Serializable
enum class CountryEngineKind(val tag: String) {
    /** Un servidor OpenVPN (un archivo `.ovpn`) alcanzado a través del proxy, con el cliente propio de la app. */
    OPENVPN("openvpn"),

    /** Un nodo de salida de una red de Tailscale (o de un servidor Headscale), alcanzado a través del proxy. */
    TAILSCALE("tailscale"),
    ;

    companion object {
        fun fromTag(tag: String): CountryEngineKind? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Cómo sale un perfil por «Otro País»: el motor y lo no sensible de su configuración. Los secretos de cada motor van
 * aparte, en el almacén seguro ([CountryExitSecrets]).
 *
 * Se guarda en el JSON del perfil con un campo `type`; los perfiles anteriores a los motores no lo tienen y se leen como
 * [OpenVpn], que era lo único que había.
 */
@Serializable(with = CountryExitSerializer::class)
sealed interface CountryExit {
    val kind: CountryEngineKind

    /** Qué mostrar del destino en la lista de perfiles. */
    val summary: String

    @Serializable
    data class OpenVpn(
        /** Nombre del archivo importado, solo para mostrarlo. */
        val fileName: String,
        val username: String,
    ) : CountryExit {
        override val kind get() = CountryEngineKind.OPENVPN
        override val summary get() = fileName
    }

    @Serializable
    data class Tailscale(
        /** El servidor de control: el de Tailscale o uno propio (Headscale). */
        val controlUrl: String = DEFAULT_CONTROL_URL,
        /** El nodo de salida: su nombre, su nombre DNS o su IP 100.x. Vacío: el único que haya en la red. */
        val exitNode: String = "",
        /** Con qué nombre aparece este equipo en la red. */
        val hostname: String = DEFAULT_HOSTNAME,
    ) : CountryExit {
        override val kind get() = CountryEngineKind.TAILSCALE
        override val summary get() = exitNode.ifBlank { "Tailscale" }

        companion object {
            const val DEFAULT_CONTROL_URL = "https://controlplane.tailscale.com"
            const val DEFAULT_HOSTNAME = "proxyuci"
        }
    }
}

/**
 * Los secretos de la salida de un perfil, que se guardan en el almacén seguro. En escritorio viajan de la ventana al
 * motor junto con la configuración.
 */
@Serializable
sealed interface CountryExitSecrets {
    @Serializable
    @SerialName("openvpn")
    data class OpenVpn(val ovpn: String, val password: String) : CountryExitSecrets

    @Serializable
    @SerialName("tailscale")
    data class Tailscale(val authKey: String) : CountryExitSecrets
}

/** Escribe cada salida con su `type`, y lee sin él (perfiles antiguos) como OpenVPN. */
object CountryExitSerializer : KSerializer<CountryExit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CountryExit")

    override fun serialize(encoder: Encoder, value: CountryExit) {
        val json = (encoder as? JsonEncoder ?: throw SerializationException("CountryExit solo se guarda como JSON")).json
        val body = when (value) {
            is CountryExit.OpenVpn -> json.encodeToJsonElement(CountryExit.OpenVpn.serializer(), value)
            is CountryExit.Tailscale -> json.encodeToJsonElement(CountryExit.Tailscale.serializer(), value)
        }.jsonObject
        encoder.encodeJsonElement(JsonObject(body + (TYPE to JsonPrimitive(value.kind.tag))))
    }

    override fun deserialize(decoder: Decoder): CountryExit {
        val input = decoder as? JsonDecoder ?: throw SerializationException("CountryExit solo se lee de JSON")
        val element = input.decodeJsonElement().jsonObject
        val tag = element[TYPE]?.jsonPrimitive?.content ?: CountryEngineKind.OPENVPN.tag
        val body = JsonObject(element - TYPE)
        return when (CountryEngineKind.fromTag(tag)) {
            CountryEngineKind.OPENVPN -> input.json.decodeFromJsonElement(CountryExit.OpenVpn.serializer(), body)
            CountryEngineKind.TAILSCALE -> input.json.decodeFromJsonElement(CountryExit.Tailscale.serializer(), body)
            null -> throw SerializationException("Motor de Otro País desconocido: $tag")
        }
    }

    private const val TYPE = "type"
}
