package com.alad1nks.jaiqal.core.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidFirebaseAuthProvider(
    private val firebaseAuth: FirebaseAuth,
) : AuthProvider {
    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    @Suppress("unused")
    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        mutableAuthState.value = auth.currentUser.toAuthState()
    }.also(firebaseAuth::addAuthStateListener)

    override suspend fun signUp(email: String, password: String) {
        val result = firebaseAuth.createUserWithEmailAndPassword(email, password).awaitResult()
        val user = result.user ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        user.sendEmailVerification().awaitResult()
    }

    override suspend fun signIn(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password).awaitResult()
    }

    override suspend fun sendPasswordReset(email: String) {
        firebaseAuth.sendPasswordResetEmail(email).awaitResult()
    }

    override suspend fun sendEmailVerification() {
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        user.sendEmailVerification().awaitResult()
    }

    override suspend fun reloadUser() {
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        user.reload().awaitResult()
        mutableAuthState.value = firebaseAuth.currentUser.toAuthState()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        val user = firebaseAuth.currentUser ?: return null
        return user.getIdToken(forceRefresh).awaitResult().token
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
        mutableAuthState.value = AuthState.Unauthenticated
    }
}

private fun FirebaseUser?.toAuthState(): AuthState = if (this == null) {
    AuthState.Unauthenticated
} else {
    AuthState.Authenticated(email = email, emailVerified = isEmailVerified)
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception.toAuthException())
        }
    }
}

private fun Throwable?.toAuthException(): AuthException {
    val code = (this as? FirebaseAuthException)?.errorCode
    val mapped = when (code) {
        "ERROR_INVALID_EMAIL" -> AuthErrorCode.INVALID_EMAIL
        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> AuthErrorCode.INVALID_CREDENTIALS
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthErrorCode.EMAIL_ALREADY_IN_USE
        "ERROR_WEAK_PASSWORD" -> AuthErrorCode.WEAK_PASSWORD
        "ERROR_USER_DISABLED" -> AuthErrorCode.USER_DISABLED
        "ERROR_TOO_MANY_REQUESTS" -> AuthErrorCode.TOO_MANY_REQUESTS
        "ERROR_NETWORK_REQUEST_FAILED" -> AuthErrorCode.NETWORK
        else -> AuthErrorCode.UNKNOWN
    }
    return AuthException(mapped, this)
}
