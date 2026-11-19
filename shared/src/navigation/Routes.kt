package org.uci.proxy.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Las pantallas de la app. Cada una es una clave serializable de Navigation 3, para que la pila de pantallas sobreviva
 * a que el sistema recree la actividad.
 */
@Serializable
internal sealed interface Route : NavKey {
    @Serializable
    data object ProfileList : Route

    /** [profileId] null significa perfil nuevo. */
    @Serializable
    data class ProfileEditor(val profileId: String?) : Route

    /** Las preferencias del usuario. */
    @Serializable
    data object Settings : Route

    /** El registro en vivo. */
    @Serializable
    data object Logs : Route

    /** El catálogo de componentes; solo en modo desarrollo. */
    @Serializable
    data object Catalog : Route
}

/**
 * Cómo guardar la pila de pantallas. En Android se podría serializar por reflexión, pero en iOS no: hay que registrar las
 * rutas. Con una `sealed interface` basta con declarar la jerarquía.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val routesConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed<Route>()
        }
    }
}
