package com.alad1nks.jaiqal.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay

class FakeAuthProvider(initialState: AuthState = AuthState.Unauthenticated) : AuthProvider {
    private val mutableAuthState = MutableStateFlow(initialState)
    override val authState: StateFlow<AuthState> = mutableAuthState

    var idToken: String? = "fake-id-token"
    var refreshedIdToken: String? = null
    var forceRefreshDelayMillis: Long = 0
    var federatedSignInDelayMillis: Long = 0
    var federatedFailure: Throwable? = null
    val tokenRequests = mutableListOf<Boolean>()
    var lastEmail: String? = null
    var lastPassword: String? = null
    var lastFederatedAuthMethod: FederatedAuthMethod? = null
    val federatedAuthMethods = mutableListOf<FederatedAuthMethod>()
    var verificationEmailsSent: Int = 0
    var resetEmailsSent: Int = 0
    val deletionReauthenticationPasswords = mutableListOf<String?>()
    var accountDeletionFailure: Throwable? = null
    var firebaseUsersDeleted: Int = 0

    override suspend fun signUp(email: String, password: String) {
        lastEmail = email
        lastPassword = password
        verificationEmailsSent += 1
        mutableAuthState.value = AuthState.Authenticated(email, emailVerified = false)
    }

    override suspend fun signIn(email: String, password: String) {
        lastEmail = email
        lastPassword = password
        mutableAuthState.value = AuthState.Authenticated(email, emailVerified = true)
    }

    override suspend fun signIn(method: FederatedAuthMethod) {
        lastFederatedAuthMethod = method
        federatedAuthMethods += method
        if (federatedSignInDelayMillis > 0) delay(federatedSignInDelayMillis)
        federatedFailure?.let { throw it }
        mutableAuthState.value = AuthState.Authenticated(email = null, emailVerified = true)
    }

    override suspend fun sendPasswordReset(email: String) {
        lastEmail = email
        resetEmailsSent += 1
    }

    override suspend fun sendEmailVerification() {
        verificationEmailsSent += 1
    }

    override suspend fun reloadUser() {
        val current = mutableAuthState.value as? AuthState.Authenticated ?: return
        mutableAuthState.value = current.copy(emailVerified = true)
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        tokenRequests += forceRefresh
        if (forceRefresh) {
            if (forceRefreshDelayMillis > 0) delay(forceRefreshDelayMillis)
            refreshedIdToken?.let { idToken = it }
        }
        return idToken
    }

    override suspend fun signOut() {
        mutableAuthState.value = AuthState.Unauthenticated
    }

    override suspend fun reauthenticateForAccountDeletion(password: String?) {
        deletionReauthenticationPasswords += password
    }

    override suspend fun deleteCurrentUser() {
        accountDeletionFailure?.let { throw it }
        firebaseUsersDeleted += 1
        mutableAuthState.value = AuthState.Unauthenticated
    }

    fun emit(state: AuthState) {
        mutableAuthState.value = state
    }
}
