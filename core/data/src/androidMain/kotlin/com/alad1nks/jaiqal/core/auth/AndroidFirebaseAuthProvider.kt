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

internal data class AndroidFirebaseUser(
    val email: String?,
    val emailVerified: Boolean,
)

internal interface AndroidAuthStateSubscription

internal interface AndroidFirebaseAuthBridge {
    fun addAuthStateListener(listener: (AndroidFirebaseUser?) -> Unit): AndroidAuthStateSubscription
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun sendEmailVerification()
    suspend fun reloadUser(): AndroidFirebaseUser?
    suspend fun getIdToken(forceRefresh: Boolean): String?
    fun signOut()
}

class AndroidFirebaseAuthProvider private constructor(
    private val bridge: AndroidFirebaseAuthBridge,
) : AuthProvider {
    constructor(firebaseAuth: FirebaseAuth) : this(FirebaseSdkAuthBridge(firebaseAuth))

    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    @Suppress("unused")
    private val authStateSubscription = bridge.addAuthStateListener { user ->
        mutableAuthState.value = user.toAuthState()
    }

    override suspend fun signUp(email: String, password: String) = bridge.signUp(email, password)
    override suspend fun signIn(email: String, password: String) = bridge.signIn(email, password)
    override suspend fun sendPasswordReset(email: String) = bridge.sendPasswordReset(email)
    override suspend fun sendEmailVerification() = bridge.sendEmailVerification()

    override suspend fun reloadUser() {
        mutableAuthState.value = bridge.reloadUser().toAuthState()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? = bridge.getIdToken(forceRefresh)

    override suspend fun signOut() {
        bridge.signOut()
        mutableAuthState.value = AuthState.Unauthenticated
    }

    internal companion object {
        internal fun fromBridge(bridge: AndroidFirebaseAuthBridge): AndroidFirebaseAuthProvider =
            AndroidFirebaseAuthProvider(bridge)
    }
}

private class FirebaseSdkAuthBridge(
    private val firebaseAuth: FirebaseAuth,
) : AndroidFirebaseAuthBridge {
    override fun addAuthStateListener(listener: (AndroidFirebaseUser?) -> Unit): AndroidAuthStateSubscription {
        val sdkListener = FirebaseAuth.AuthStateListener { auth -> listener(auth.currentUser.toBridgeUser()) }
        firebaseAuth.addAuthStateListener(sdkListener)
        return object : AndroidAuthStateSubscription {}
    }

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

    override suspend fun reloadUser(): AndroidFirebaseUser? {
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        user.reload().awaitResult()
        return firebaseAuth.currentUser.toBridgeUser()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        val user = firebaseAuth.currentUser ?: return null
        return user.getIdToken(forceRefresh).awaitResult().token
    }

    override fun signOut() = firebaseAuth.signOut()
}

private fun FirebaseUser?.toBridgeUser(): AndroidFirebaseUser? = this?.let {
    AndroidFirebaseUser(email = it.email, emailVerified = it.isEmailVerified)
}

private fun AndroidFirebaseUser?.toAuthState(): AuthState = if (this == null) {
    AuthState.Unauthenticated
} else {
    AuthState.Authenticated(email = email, emailVerified = emailVerified)
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception.toAuthException())
    }
}

private fun Throwable?.toAuthException(): AuthException {
    val mapped = when ((this as? FirebaseAuthException)?.errorCode) {
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
