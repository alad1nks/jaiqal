package com.alad1nks.jaiqal.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(
        val email: String?,
        val emailVerified: Boolean,
        val method: AccountAuthMethod = AccountAuthMethod.UNKNOWN,
    ) : AuthState
}

enum class AccountAuthMethod {
    PASSWORD,
    GOOGLE,
    APPLE,
    UNKNOWN,
}

enum class FederatedAuthMethod {
    GOOGLE,
    APPLE,
}

enum class AuthErrorCode {
    INVALID_EMAIL,
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_IN_USE,
    WEAK_PASSWORD,
    USER_DISABLED,
    TOO_MANY_REQUESTS,
    NETWORK,
    NO_CURRENT_USER,
    NOT_CONFIGURED,
    CANCELLED,
    PROVIDER_UNAVAILABLE,
    ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
    CREDENTIAL_ALREADY_IN_USE,
    INVALID_NONCE,
    REAUTHENTICATION_REQUIRED,
    UNKNOWN,
}

class AuthException(
    val code: AuthErrorCode,
    cause: Throwable? = null,
) : RuntimeException("Firebase authentication failed: ${code.name}", cause)

interface AuthProvider {
    val authState: StateFlow<AuthState>

    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun signIn(method: FederatedAuthMethod)
    suspend fun sendPasswordReset(email: String)
    suspend fun sendEmailVerification()
    suspend fun reloadUser()
    suspend fun getIdToken(forceRefresh: Boolean = false): String?
    suspend fun signOut()

    suspend fun reauthenticateForAccountDeletion(password: String?): Unit = providerUnavailable()
    suspend fun deleteCurrentUser(): Unit = providerUnavailable()

    private fun providerUnavailable(): Nothing = throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
}

class UnavailableAuthProvider : AuthProvider {
    override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Unauthenticated)

    override suspend fun signUp(email: String, password: String) = unavailable()
    override suspend fun signIn(email: String, password: String) = unavailable()
    override suspend fun signIn(method: FederatedAuthMethod) = providerUnavailable()
    override suspend fun sendPasswordReset(email: String) = unavailable()
    override suspend fun sendEmailVerification() = unavailable()
    override suspend fun reloadUser() = unavailable()
    override suspend fun getIdToken(forceRefresh: Boolean): String? = unavailable()
    override suspend fun signOut() = Unit
    override suspend fun reauthenticateForAccountDeletion(password: String?) = providerUnavailable()
    override suspend fun deleteCurrentUser() = providerUnavailable()

    private fun unavailable(): Nothing = throw AuthException(AuthErrorCode.NOT_CONFIGURED)
    private fun providerUnavailable(): Nothing = throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
}
