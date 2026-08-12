package com.alad1nks.jaiqal.core.auth

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

data class IosFirebaseUser(
    val email: String?,
    val emailVerified: Boolean,
)

interface IosAuthStateSubscription {
    fun cancel()
}

interface IosFirebaseAuthBridge {
    fun addAuthStateListener(listener: (IosFirebaseUser?) -> Unit): IosAuthStateSubscription
    fun signUp(email: String, password: String, completion: (String?) -> Unit)
    fun signIn(email: String, password: String, completion: (String?) -> Unit)
    fun sendPasswordReset(email: String, completion: (String?) -> Unit)
    fun sendEmailVerification(completion: (String?) -> Unit)
    fun reloadUser(completion: (IosFirebaseUser?, String?) -> Unit)
    fun getIdToken(forceRefresh: Boolean, completion: (String?, String?) -> Unit)
    fun signOut(): String?
}

class IosFirebaseAuthProvider(
    private val bridge: IosFirebaseAuthBridge,
) : AuthProvider {
    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    @Suppress("unused")
    private val subscription = bridge.addAuthStateListener { user ->
        mutableAuthState.value = user.toAuthState()
    }

    override suspend fun signUp(email: String, password: String) = awaitError { completion ->
        bridge.signUp(email, password, completion)
    }

    override suspend fun signIn(email: String, password: String) = awaitError { completion ->
        bridge.signIn(email, password, completion)
    }

    override suspend fun sendPasswordReset(email: String) = awaitError { completion ->
        bridge.sendPasswordReset(email, completion)
    }

    override suspend fun sendEmailVerification() = awaitError(bridge::sendEmailVerification)

    override suspend fun reloadUser() = suspendCancellableCoroutine { continuation ->
        bridge.reloadUser { user, errorCode ->
            if (!continuation.isActive) return@reloadUser
            if (errorCode == null) {
                mutableAuthState.value = user.toAuthState()
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(errorCode.toAuthException())
            }
        }
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? = suspendCancellableCoroutine { continuation ->
        bridge.getIdToken(forceRefresh) { token, errorCode ->
            if (!continuation.isActive) return@getIdToken
            if (errorCode == null) continuation.resume(token)
            else continuation.resumeWithException(errorCode.toAuthException())
        }
    }

    override suspend fun signOut() {
        val errorCode = bridge.signOut()
        if (errorCode != null) throw errorCode.toAuthException()
        mutableAuthState.value = AuthState.Unauthenticated
    }

    private suspend fun awaitError(action: (((String?) -> Unit)) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            action { errorCode ->
                if (!continuation.isActive) return@action
                if (errorCode == null) continuation.resume(Unit)
                else continuation.resumeWithException(errorCode.toAuthException())
            }
        }
}

private fun IosFirebaseUser?.toAuthState(): AuthState = if (this == null) {
    AuthState.Unauthenticated
} else {
    AuthState.Authenticated(email, emailVerified)
}

private fun String.toAuthException(): AuthException = AuthException(
    when (this) {
        "invalid-email" -> AuthErrorCode.INVALID_EMAIL
        "invalid-credentials" -> AuthErrorCode.INVALID_CREDENTIALS
        "email-already-in-use" -> AuthErrorCode.EMAIL_ALREADY_IN_USE
        "weak-password" -> AuthErrorCode.WEAK_PASSWORD
        "user-disabled" -> AuthErrorCode.USER_DISABLED
        "too-many-requests" -> AuthErrorCode.TOO_MANY_REQUESTS
        "network" -> AuthErrorCode.NETWORK
        "no-current-user" -> AuthErrorCode.NO_CURRENT_USER
        else -> AuthErrorCode.UNKNOWN
    },
)
