package org.uci.proxy.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * La pila de pantallas se guarda al recrear la actividad. En Android se podría serializar por reflexión, pero en iOS no:
 * si una ruta no está registrada en [routesConfiguration], la app falla justo al restaurarla, y solo allí.
 */
class RoutesTest {
    private val json = Json { serializersModule = routesConfiguration.serializersModule }
    private val serializer = PolymorphicSerializer(NavKey::class)

    private fun roundTrip(key: NavKey): NavKey =
        json.decodeFromString(serializer, json.encodeToString(serializer, key))

    @Test
    fun `every screen survives being saved and restored`() {
        val screens = listOf(Route.ProfileList, Route.Logs, Route.Settings, Route.ProfileEditor(profileId = null), Route.ProfileEditor(profileId = "abc-123"))

        screens.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Serializable
    private data object NotRegistered : NavKey

    @Test
    fun `a screen missing from the configuration cannot be saved`() {
        // Si alguien crea una ruta fuera de Route, esta prueba recuerda por qué falla en iOS.
        assertFailsWith<SerializationException> { json.encodeToString(serializer, NotRegistered) }
    }
}
