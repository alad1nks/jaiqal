package com.alad1nks.jaiqal.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SessionState { LOADING, UNAUTHENTICATED, AUTHENTICATED }

data class AppUiState(
    val session: SessionState = SessionState.LOADING,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

class AppViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    fun finishStartup(authenticated: Boolean = false) {
        mutableState.update {
            it.copy(session = if (authenticated) SessionState.AUTHENTICATED else SessionState.UNAUTHENTICATED)
        }
    }

    fun setTheme(mode: ThemeMode) {
        mutableState.update { it.copy(themeMode = mode) }
    }
}

@Stable
class JaiqalAppState(
    val navController: NavHostController,
    val snackbarHostState: SnackbarHostState,
)

@Composable
fun rememberJaiqalAppState(
    navController: NavHostController = rememberNavController(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) = remember(navController, snackbarHostState) {
    JaiqalAppState(navController, snackbarHostState)
}
