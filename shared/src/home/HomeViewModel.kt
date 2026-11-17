package org.uci.proxy.home

import androidx.lifecycle.ViewModel
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo

data class HomeUiState(
    val platformName: String,
    val osVersion: String,
)

class HomeViewModel(
    platformInfo: PlatformInfo,
    logger: Logger,
) : ViewModel() {
    val uiState = HomeUiState(
        platformName = platformInfo.kind.displayName,
        osVersion = platformInfo.osVersion,
    )

    init {
        logger.log(LogLevel.INFO, TAG, "ProxyUCI en ${uiState.platformName} ${uiState.osVersion}")
    }

    private companion object {
        const val TAG = "ProxyUCI"
    }
}
