package com.alad1nks.jaiqal.core.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAuthProvider(initialState: AuthState = AuthState.Unauthenticated) : AuthProvider {
    private val mutableAuthState = MutableStateFlow(initialState)
    override val authState: StateFlow<AuthState> = mutableAuthState

    var idToken: String? = "fake-id-token"
    var refreshedIdToken: String? = null
    var forceRefreshDelayMillis: Long = 0
    val tokenRequests = mutableListOf<Boolean>()
    var lastEmail: String? = null
    var lastPassword: String? = null
    var verificationEmailsSent: Int = 0
    var resetEmailsSent: Int = 0

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

    fun emit(state: AuthState) {
        mutableAuthState.value = state
    }
}
