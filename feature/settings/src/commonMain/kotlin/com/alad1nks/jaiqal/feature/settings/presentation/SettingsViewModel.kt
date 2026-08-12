package com.alad1nks.jaiqal.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.AuthException
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.UserSession
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.config.AppInfo
import com.alad1nks.jaiqal.core.network.BackendConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsUiError { NETWORK, TOO_MANY_REQUESTS, NO_USER, UNKNOWN }

data class SettingsUiState(
    val user: UserSession? = null,
    val emailVerified: Boolean = false,
    val appVersion: String,
    val privacyPolicyUrl: String?,
    val diagnostics: DebugDiagnostics? = null,
    val isSendingVerification: Boolean = false,
    val verificationSent: Boolean = false,
    val isSigningOut: Boolean = false,
    val error: SettingsUiError? = null,
)

data class DebugDiagnostics(
    val platform: String,
    val environment: String,
    val backendBaseUrl: String,
)

class SettingsViewModel(
    backendConfig: BackendConfig,
    appInfo: AppInfo,
    private val authProvider: AuthProvider,
    userSessionStore: UserSessionStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            appVersion = appInfo.version,
            privacyPolicyUrl = appInfo.privacyPolicyUrl,
            diagnostics = if (appInfo.isDebug) DebugDiagnostics(
                platform = appInfo.platform,
                environment = backendConfig.environment.name.lowercase(),
                backendBaseUrl = backendConfig.baseUrl,
            ) else null,
        ),
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(userSessionStore.session, authProvider.authState) { session, auth -> session to auth }
                .collect { (session, auth) ->
                    mutableState.update {
                        it.copy(
                            user = session,
                            emailVerified = (auth as? AuthState.Authenticated)?.emailVerified
                                ?: session?.emailVerified
                                ?: false,
                        )
                    }
                }
        }
    }

    fun resendVerificationEmail() {
        if (mutableState.value.isSendingVerification) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(isSendingVerification = true, verificationSent = false, error = null)
            }
            try {
                authProvider.sendEmailVerification()
                mutableState.update { it.copy(isSendingVerification = false, verificationSent = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update { it.copy(isSendingVerification = false, error = failure.toSettingsError()) }
            }
        }
    }

    fun signOut() {
        if (mutableState.value.isSigningOut) return
        viewModelScope.launch {
            mutableState.update { it.copy(isSigningOut = true, error = null) }
            try {
                authProvider.signOut()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update { it.copy(isSigningOut = false, error = failure.toSettingsError()) }
            }
        }
    }
}

private fun Throwable.toSettingsError(): SettingsUiError = when ((this as? AuthException)?.code) {
    AuthErrorCode.NETWORK -> SettingsUiError.NETWORK
    AuthErrorCode.TOO_MANY_REQUESTS -> SettingsUiError.TOO_MANY_REQUESTS
    AuthErrorCode.NO_CURRENT_USER -> SettingsUiError.NO_USER
    else -> SettingsUiError.UNKNOWN
}
