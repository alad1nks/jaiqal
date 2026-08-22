package com.alad1nks.jaiqal.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.AuthException
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.AccountAuthMethod
import com.alad1nks.jaiqal.core.auth.AccountDeletionCoordinator
import com.alad1nks.jaiqal.core.auth.UserSession
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.config.AppInfo
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsUiError { NETWORK, TOO_MANY_REQUESTS, NO_USER, INVALID_CREDENTIALS, UNKNOWN }

data class SettingsUiState(
    val user: UserSession? = null,
    val emailVerified: Boolean = false,
    val appVersion: String,
    val privacyPolicyUrl: String?,
    val diagnostics: DebugDiagnostics? = null,
    val isSendingVerification: Boolean = false,
    val verificationSent: Boolean = false,
    val isSigningOut: Boolean = false,
    val authMethod: AccountAuthMethod = AccountAuthMethod.UNKNOWN,
    val showDeleteConfirmation: Boolean = false,
    val isDeletingAccount: Boolean = false,
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
    private val accountDeletion: AccountDeletionCoordinator,
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
                            authMethod = (auth as? AuthState.Authenticated)?.method
                                ?: AccountAuthMethod.UNKNOWN,
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

    fun requestAccountDeletion() {
        if (mutableState.value.isDeletingAccount) return
        mutableState.update { it.copy(showDeleteConfirmation = true, error = null) }
    }

    fun cancelAccountDeletion() {
        if (mutableState.value.isDeletingAccount) return
        mutableState.update { it.copy(showDeleteConfirmation = false, error = null) }
    }

    fun confirmAccountDeletion(password: String?) {
        if (mutableState.value.isDeletingAccount) return
        viewModelScope.launch {
            mutableState.update { it.copy(isDeletingAccount = true, error = null) }
            try {
                accountDeletion.deleteAccount(password)
                mutableState.update {
                    it.copy(isDeletingAccount = false, showDeleteConfirmation = false)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                val cancelled = (failure as? AuthException)?.code == AuthErrorCode.CANCELLED
                mutableState.update {
                    it.copy(
                        isDeletingAccount = false,
                        error = if (cancelled) null else failure.toSettingsError(),
                    )
                }
            }
        }
    }
}

private fun Throwable.toSettingsError(): SettingsUiError {
    val authError = (this as? AuthException)?.code
    return when {
        authError == AuthErrorCode.NETWORK -> SettingsUiError.NETWORK
        authError == AuthErrorCode.TOO_MANY_REQUESTS -> SettingsUiError.TOO_MANY_REQUESTS
        authError == AuthErrorCode.NO_CURRENT_USER -> SettingsUiError.NO_USER
        authError == AuthErrorCode.INVALID_CREDENTIALS ||
            authError == AuthErrorCode.REAUTHENTICATION_REQUIRED -> SettingsUiError.INVALID_CREDENTIALS
        this is ApiException.Connectivity || this is ApiException.Timeout -> SettingsUiError.NETWORK
        this is ApiException.Backend && statusCode == 429 -> SettingsUiError.TOO_MANY_REQUESTS
        else -> SettingsUiError.UNKNOWN
    }
}
