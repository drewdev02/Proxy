package org.uci.proxy.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** Sin estado propio: recibe lo que pinta, así se puede previsualizar y probar sin Koin. */
@Composable
fun HomeScreen(state: HomeUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .safeContentPadding()
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ProxyUCI", style = MaterialTheme.typography.headlineMedium)
        Text(
            "en ${state.platformName} ${state.osVersion}",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(HomeUiState(platformName = "macOS", osVersion = "27.2"))
    }
}
