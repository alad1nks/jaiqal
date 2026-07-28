package com.alad1nks.jaiqal.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.AuthException
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMessage { RESET_EMAIL_SENT, VERIFICATION_EMAIL_SENT }

data class AuthFormUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: AuthErrorCode? = null,
    val message: AuthMessage? = null,
)

class AuthViewModel(private val authProvider: AuthProvider) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthFormUiState())
    val state: StateFlow<AuthFormUiState> = mutableState.asStateFlow()

    fun setEmail(value: String) = mutableState.update { it.copy(email = value, error = null, message = null) }
    fun setPassword(value: String) = mutableState.update { it.copy(password = value, error = null, message = null) }

    fun signIn() = runAuthAction(requirePassword = true) { email, password ->
        authProvider.signIn(email, password)
    }

    fun signUp() = runAuthAction(requirePassword = true) { email, password ->
        authProvider.signUp(email, password)
    }

    fun sendPasswordReset() = runAuthAction(requirePassword = false) { email, _ ->
        authProvider.sendPasswordReset(email)
        mutableState.update { it.copy(message = AuthMessage.RESET_EMAIL_SENT) }
    }

    private fun runAuthAction(
        requirePassword: Boolean,
        action: suspend (email: String, password: String) -> Unit,
    ) {
        if (mutableState.value.isLoading) return
        val email = mutableState.value.email.trim()
        val password = mutableState.value.password
        val validationError = when {
            !EMAIL_PATTERN.matches(email) -> AuthErrorCode.INVALID_EMAIL
            requirePassword && password.length < FIREBASE_MIN_PASSWORD_LENGTH -> AuthErrorCode.WEAK_PASSWORD
            else -> null
        }
        if (validationError != null) {
            mutableState.update { it.copy(error = validationError) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null, message = null) }
            runCatching { action(email, password) }
                .onFailure { failure ->
                    mutableState.update { it.copy(error = (failure as? AuthException)?.code ?: AuthErrorCode.UNKNOWN) }
                }
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    private companion object {
        const val FIREBASE_MIN_PASSWORD_LENGTH = 6
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

data class VerifyEmailUiState(
    val email: String? = null,
    val isLoading: Boolean = false,
    val error: AuthErrorCode? = null,
    val message: AuthMessage? = null,
)

class VerifyEmailViewModel(private val authProvider: AuthProvider) : ViewModel() {
    private val mutableState = MutableStateFlow(VerifyEmailUiState())
    val state: StateFlow<VerifyEmailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            authProvider.authState.collectLatest { authState ->
                mutableState.update {
                    it.copy(email = (authState as? AuthState.Authenticated)?.email)
                }
            }
        }
    }

    fun resendVerification() = runAction(AuthMessage.VERIFICATION_EMAIL_SENT) {
        authProvider.sendEmailVerification()
    }

    fun reloadUser() = runAction(message = null) {
        authProvider.reloadUser()
    }

    fun signOut() = runAction(message = null) {
        authProvider.signOut()
    }

    private fun runAction(message: AuthMessage?, action: suspend () -> Unit) {
        if (mutableState.value.isLoading) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null, message = null) }
            runCatching { action() }
                .onSuccess { mutableState.update { it.copy(message = message) } }
                .onFailure { failure ->
                    mutableState.update { it.copy(error = (failure as? AuthException)?.code ?: AuthErrorCode.UNKNOWN) }
                }
            mutableState.update { it.copy(isLoading = false) }
        }
    }
}
