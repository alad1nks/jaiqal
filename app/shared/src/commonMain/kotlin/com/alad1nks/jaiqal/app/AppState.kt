package com.alad1nks.jaiqal.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SessionState {
    LOADING,
    UNAUTHENTICATED,
    EMAIL_VERIFICATION_REQUIRED,
    AUTHENTICATING_BACKEND,
    AUTHENTICATED,
    ERROR,
}

data class AppUiState(
    val session: SessionState = SessionState.LOADING,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

class AppViewModel(
    private val authProvider: AuthProvider,
    private val currentUserGateway: CurrentUserGateway,
    private val userSessionStore: UserSessionStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var synchronizationJob: Job? = null

    init {
        viewModelScope.launch {
            authProvider.authState.collectLatest(::handleAuthState)
        }
    }

    private suspend fun handleAuthState(authState: AuthState) {
        synchronizationJob?.cancel()
        when (authState) {
            AuthState.Loading -> mutableState.update { it.copy(session = SessionState.LOADING) }
            AuthState.Unauthenticated -> {
                userSessionStore.clear()
                mutableState.update { it.copy(session = SessionState.UNAUTHENTICATED) }
            }
            is AuthState.Authenticated -> {
                if (!authState.emailVerified) {
                    userSessionStore.clear()
                    mutableState.update {
                        it.copy(session = SessionState.EMAIL_VERIFICATION_REQUIRED)
                    }
                } else {
                    synchronizeBackendSession()
                }
            }
        }
    }

    private suspend fun synchronizeBackendSession() {
        mutableState.update { it.copy(session = SessionState.AUTHENTICATING_BACKEND) }
        runCatching {
            val idToken = authProvider.getIdToken(forceRefresh = true)
                ?: error("Firebase did not provide an ID Token")
            currentUserGateway.fetchCurrentUser(idToken)
        }.onSuccess { user ->
            userSessionStore.set(user)
            mutableState.update { it.copy(session = SessionState.AUTHENTICATED) }
        }.onFailure {
            userSessionStore.clear()
            mutableState.update { it.copy(session = SessionState.ERROR) }
        }
    }

    fun retrySession() {
        synchronizationJob?.cancel()
        synchronizationJob = viewModelScope.launch {
            val authState = authProvider.authState.value
            if (authState is AuthState.Authenticated && authState.emailVerified) {
                synchronizeBackendSession()
            }
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
