package org.uci.proxy.profile

import org.uci.proxy.common.StorageFailure
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.proxy.ProxyTestResult

sealed interface ProxyTestUiState {
    data object Idle : ProxyTestUiState

    data object Running : ProxyTestUiState

    data class Finished(val result: ProxyTestResult) : ProxyTestUiState

    /** La persona no concedió el acceso a la red local, así que la prueba no llegó a hacerse. */
    data object LocalNetworkDenied : ProxyTestUiState
}

data class ProfileEditorUiState(
    val isNew: Boolean = true,
    val draft: ProfileDraft = ProfileDraft(),
    val password: String = "",
    val errors: Set<ProfileField> = emptySet(),
    val isSaving: Boolean = false,
    /** Por qué falló el último guardado; null si no falló. */
    val saveFailure: StorageFailure? = null,
    /** Pasa a true cuando el perfil quedó guardado; la pantalla lo usa para cerrarse. */
    val saved: Boolean = false,
    val test: ProxyTestUiState = ProxyTestUiState.Idle,
    val vpn: VpnDraft = VpnDraft(),
    val routing: RoutingDraft = RoutingDraft(),
    /** Los motores de Otro País que hay en este equipo, en el orden en que se ofrecen. */
    val engines: List<CountryEngineKind> = listOf(CountryEngineKind.OPENVPN),
    /** Consultar la cuota de la UCI con este perfil; null: según el proxy (sí, si es de la UCI). */
    val uciQuota: Boolean? = null,
) {
    val saveFailed: Boolean get() = saveFailure != null

    val isBusy: Boolean get() = isSaving || test == ProxyTestUiState.Running

    /** Lo que se hará con la cuota, con lo escrito ahora en el formulario. */
    val checksUciQuota: Boolean get() = uciQuota ?: ProxyProfile("", "", draft.host.trim(), 0, draft.protocol, "").isUciProxy
}
