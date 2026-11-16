package org.uci.proxy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.home.HomeViewModel
import org.uci.proxy.profile.ProfileEditorScreen
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListScreen
import org.uci.proxy.profile.ProfileListViewModel

private sealed interface Screen {
    data object ProfileList : Screen

    /** [profileId] null significa perfil nuevo. */
    data class ProfileEditor(val profileId: String?) : Screen
}

/** Requiere que la app haya llamado antes a [org.uci.proxy.di.initKoin]. */
@Composable
@DevelopmentEntryPoint
fun App() {
    MaterialTheme {
        var screen by remember { mutableStateOf<Screen>(Screen.ProfileList) }

        when (val current = screen) {
            Screen.ProfileList -> ProfileListRoute(
                onAdd = { screen = Screen.ProfileEditor(profileId = null) },
                onEdit = { screen = Screen.ProfileEditor(profileId = it) },
            )
            is Screen.ProfileEditor -> ProfileEditorRoute(
                profileId = current.profileId,
                onClose = { screen = Screen.ProfileList },
            )
        }
    }
}

@Composable
private fun ProfileListRoute(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val viewModel = koinViewModel<ProfileListViewModel>()
    val home = koinViewModel<HomeViewModel>()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val deleteFailed by viewModel.deleteFailed.collectAsStateWithLifecycle()

    ProfileListScreen(
        profiles = profiles,
        platformLabel = "ProxyUCI en ${home.uiState.platformName} ${home.uiState.osVersion}",
        deleteFailed = deleteFailed,
        onAdd = onAdd,
        onEdit = onEdit,
        onDelete = viewModel::delete,
        onDismissDeleteError = viewModel::dismissDeleteError,
    )
}

@Composable
private fun ProfileEditorRoute(profileId: String?, onClose: () -> Unit) {
    val viewModel = koinViewModel<ProfileEditorViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // El view model se reutiliza entre aperturas: cada vez que se entra se prepara para ese perfil.
    LaunchedEffect(profileId) { viewModel.start(profileId) }
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    ProfileEditorScreen(
        state = state,
        onDraftChange = viewModel::onDraftChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTest = viewModel::testProxy,
        onSave = viewModel::save,
        onCancel = onClose,
    )
}
