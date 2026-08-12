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
import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.core.cache.OfflineCache
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.core.diagnostics.CrashReporter
import com.alad1nks.jaiqal.core.diagnostics.NoOpCrashReporter
import com.alad1nks.jaiqal.core.diagnostics.NonFatalIssue
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import com.alad1nks.jaiqal.core.preferences.AppLanguage
import com.alad1nks.jaiqal.core.preferences.AppPreferences
import com.alad1nks.jaiqal.core.preferences.AppThemePreference
import com.alad1nks.jaiqal.core.preferences.InMemoryAppPreferences
import kotlinx.coroutines.CancellationException
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
    val language: AppLanguage = AppLanguage.SYSTEM,
    val preferencesLoaded: Boolean = false,
)

class AppViewModel(
    private val authProvider: AuthProvider,
    private val currentUserGateway: CurrentUserGateway,
    private val userSessionStore: UserSessionStore,
    private val sessionErrorStore: SessionErrorStore,
    private val offlineCache: OfflineCache,
    private val appPreferences: AppPreferences = InMemoryAppPreferences(),
    private val crashReporter: CrashReporter = NoOpCrashReporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var synchronizationJob: Job? = null

    init {
        viewModelScope.launch {
            appPreferences.state.collectLatest { preferences ->
                mutableState.update {
                    it.copy(
                        themeMode = preferences.theme.toThemeMode(),
                        language = preferences.language,
                        preferencesLoaded = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            authProvider.authState.collectLatest(::handleAuthState)
        }
        viewModelScope.launch {
            sessionErrorStore.requiresSignIn.collectLatest { requiresSignIn ->
                if (requiresSignIn) {
                    userSessionStore.clear()
                    mutableState.update { it.copy(session = SessionState.ERROR) }
                }
            }
        }
    }

    private suspend fun handleAuthState(authState: AuthState) {
        synchronizationJob?.cancel()
        when (authState) {
            AuthState.Loading -> mutableState.update { it.copy(session = SessionState.LOADING) }
            AuthState.Unauthenticated -> {
                sessionErrorStore.clear()
                clearCurrentAccount()
                mutableState.update { it.copy(session = SessionState.UNAUTHENTICATED) }
            }
            is AuthState.Authenticated -> {
                if (!authState.emailVerified) {
                    sessionErrorStore.clear()
                    clearCurrentAccount()
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
        try {
            val user = currentUserGateway.fetchCurrentUser()
            sessionErrorStore.clear()
            cacheUserBestEffort(user)
            userSessionStore.set(user)
            mutableState.update { it.copy(session = SessionState.AUTHENTICATED) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (failure !is ApiException) {
                try {
                    crashReporter.recordNonFatal(NonFatalIssue.BACKEND_SESSION_SYNC)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Diagnostics must never interrupt session recovery.
                }
            }
            userSessionStore.clear()
            mutableState.update { it.copy(session = SessionState.ERROR) }
        }
    }

    fun retrySession() {
        synchronizationJob?.cancel()
        sessionErrorStore.clear()
        synchronizationJob = viewModelScope.launch {
            val authState = authProvider.authState.value
            if (authState is AuthState.Authenticated && authState.emailVerified) {
                synchronizeBackendSession()
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { appPreferences.setTheme(mode.toPreference()) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { appPreferences.setLanguage(language) }
    }

    private suspend fun clearCurrentAccount() {
        val accountId = userSessionStore.session.value?.userId
        try {
            if (accountId != null) offlineCache.clearAccount(accountId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Local cache failure must not keep an authenticated in-memory session alive.
        } finally {
            userSessionStore.clear()
        }
    }

    private suspend fun cacheUserBestEffort(user: CurrentUserResponse) {
        try {
            offlineCache.replaceUser(user)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The server response remains the source of truth when local persistence fails.
        }
    }
}

private fun AppThemePreference.toThemeMode() = when (this) {
    AppThemePreference.SYSTEM -> ThemeMode.SYSTEM
    AppThemePreference.LIGHT -> ThemeMode.LIGHT
    AppThemePreference.DARK -> ThemeMode.DARK
}

private fun ThemeMode.toPreference() = when (this) {
    ThemeMode.SYSTEM -> AppThemePreference.SYSTEM
    ThemeMode.LIGHT -> AppThemePreference.LIGHT
    ThemeMode.DARK -> AppThemePreference.DARK
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
