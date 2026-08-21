package com.alad1nks.jaiqal.core.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class AndroidFirebaseUser(
    val email: String?,
    val emailVerified: Boolean,
)

internal interface AndroidAuthStateSubscription

internal interface AndroidGoogleIdTokenSource {
    suspend fun getIdToken(filterByAuthorizedAccounts: Boolean): String
}

internal fun interface AndroidGoogleFirebaseAuthenticator {
    suspend fun signIn(idToken: String)
}

internal class NoGoogleCredentialAvailableException(cause: Throwable? = null) : RuntimeException(cause)

internal class AndroidGoogleSignInCoordinator(
    private val idTokenSource: AndroidGoogleIdTokenSource,
    private val firebaseAuthenticator: AndroidGoogleFirebaseAuthenticator,
) {
    suspend fun signIn() {
        val idToken = try {
            idTokenSource.getIdToken(filterByAuthorizedAccounts = true)
        } catch (_: NoGoogleCredentialAvailableException) {
            try {
                idTokenSource.getIdToken(filterByAuthorizedAccounts = false)
            } catch (failure: NoGoogleCredentialAvailableException) {
                throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, failure)
            }
        }
        firebaseAuthenticator.signIn(idToken)
    }
}

internal interface AndroidAppleAuthClient {
    suspend fun completePendingSignIn(): Boolean
    suspend fun startSignIn()
}

internal class AndroidAppleSignInCoordinator(
    private val client: AndroidAppleAuthClient,
) {
    private val operationLock = Any()
    private var activeOperation: CompletableDeferred<Unit>? = null

    suspend fun signIn() {
        var ownsOperation = false
        val operation = synchronized(operationLock) {
            activeOperation ?: CompletableDeferred<Unit>().also {
                activeOperation = it
                ownsOperation = true
            }
        }
        if (!ownsOperation) {
            operation.await()
            return
        }

        try {
            if (!client.completePendingSignIn()) {
                client.startSignIn()
            }
            operation.complete(Unit)
        } catch (failure: Throwable) {
            operation.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(operationLock) {
                if (activeOperation === operation) activeOperation = null
            }
        }
    }
}

internal interface AndroidFirebaseAuthBridge {
    fun addAuthStateListener(listener: (AndroidFirebaseUser?) -> Unit): AndroidAuthStateSubscription
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun signIn(method: FederatedAuthMethod)
    suspend fun sendPasswordReset(email: String)
    suspend fun sendEmailVerification()
    suspend fun reloadUser(): AndroidFirebaseUser?
    suspend fun getIdToken(forceRefresh: Boolean): String?
    fun signOut()
}

class AndroidFirebaseAuthProvider private constructor(
    private val bridge: AndroidFirebaseAuthBridge,
) : AuthProvider {
    constructor(firebaseAuth: FirebaseAuth) : this(
        FirebaseSdkAuthBridge(
            firebaseAuth = firebaseAuth,
            googleSignIn = null,
            appleSignIn = null,
        ),
    )

    constructor(
        firebaseAuth: FirebaseAuth,
        context: Context,
        activity: Activity,
        googleServerClientId: String?,
    ) : this(
        FirebaseSdkAuthBridge(
            firebaseAuth = firebaseAuth,
            googleSignIn = googleServerClientId?.takeIf(String::isNotBlank)?.let { clientId ->
                createGoogleSignInCoordinator(firebaseAuth, context, activity, clientId)
            },
            appleSignIn = createAppleSignInCoordinator(firebaseAuth, activity),
        ),
    )

    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    @Suppress("unused")
    private val authStateSubscription = bridge.addAuthStateListener { user ->
        mutableAuthState.value = user.toAuthState()
    }

    override suspend fun signUp(email: String, password: String) = bridge.signUp(email, password)
    override suspend fun signIn(email: String, password: String) = bridge.signIn(email, password)
    override suspend fun signIn(method: FederatedAuthMethod) = bridge.signIn(method)
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
    private val googleSignIn: AndroidGoogleSignInCoordinator?,
    private val appleSignIn: AndroidAppleSignInCoordinator?,
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

    override suspend fun signIn(method: FederatedAuthMethod) = when (method) {
        FederatedAuthMethod.GOOGLE -> googleSignIn?.signIn()
            ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
        FederatedAuthMethod.APPLE -> appleSignIn?.signIn()
            ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
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

private fun createGoogleSignInCoordinator(
    firebaseAuth: FirebaseAuth,
    context: Context,
    activity: Activity,
    googleServerClientId: String,
): AndroidGoogleSignInCoordinator = AndroidGoogleSignInCoordinator(
    idTokenSource = CredentialManagerGoogleIdTokenSource(
        credentialManager = CredentialManager.create(context.applicationContext),
        activity = activity,
        googleServerClientId = googleServerClientId,
    ),
    firebaseAuthenticator = AndroidGoogleFirebaseAuthenticator { idToken ->
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential).awaitResult()
    },
)

private fun createAppleSignInCoordinator(
    firebaseAuth: FirebaseAuth,
    activity: Activity,
): AndroidAppleSignInCoordinator = AndroidAppleSignInCoordinator(
    FirebaseAppleAuthClient(firebaseAuth, activity),
)

private class FirebaseAppleAuthClient(
    private val firebaseAuth: FirebaseAuth,
    activity: Activity,
) : AndroidAppleAuthClient {
    private val activityReference = WeakReference(activity)
    private var pendingResult = firebaseAuth.pendingAuthResult
    private var startedResult: Task<AuthResult>? = null

    override suspend fun completePendingSignIn(): Boolean {
        val result = pendingResult ?: startedResult ?: return false
        try {
            result.awaitResult()
        } finally {
            if (result.isComplete) {
                if (pendingResult === result) pendingResult = null
                if (startedResult === result) startedResult = null
            }
        }
        return true
    }

    override suspend fun startSignIn() {
        val activity = activityReference.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
        val provider = OAuthProvider.newBuilder("apple.com")
            .setScopes(listOf("email", "name"))
            .build()
        val result = firebaseAuth.startActivityForSignInWithProvider(activity, provider)
        startedResult = result
        try {
            result.awaitResult()
        } finally {
            if (result.isComplete && startedResult === result) startedResult = null
        }
    }
}

private class CredentialManagerGoogleIdTokenSource(
    private val credentialManager: CredentialManager,
    activity: Activity,
    private val googleServerClientId: String,
) : AndroidGoogleIdTokenSource {
    private val activityReference = WeakReference(activity)

    override suspend fun getIdToken(filterByAuthorizedAccounts: Boolean): String {
        val activity = activityReference.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(googleServerClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val response = try {
            credentialManager.getCredential(activity, request)
        } catch (failure: NoCredentialException) {
            throw NoGoogleCredentialAvailableException(failure)
        } catch (failure: GetCredentialException) {
            throw failure.toGoogleAuthException()
        }

        return response.credential.toGoogleIdToken()
    }
}

internal fun GetCredentialException.toGoogleAuthException(): AuthException = AuthException(
    code = when (this) {
        is GetCredentialCancellationException -> AuthErrorCode.CANCELLED
        is GetCredentialProviderConfigurationException,
        is GetCredentialUnsupportedException,
        -> AuthErrorCode.PROVIDER_UNAVAILABLE
        else -> AuthErrorCode.UNKNOWN
    },
    cause = this,
)

internal fun Credential.toGoogleIdToken(): String {
    val credential = this as? CustomCredential
        ?: throw AuthException(AuthErrorCode.INVALID_CREDENTIALS)
    if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw AuthException(AuthErrorCode.INVALID_CREDENTIALS)
    }
    return try {
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (failure: GoogleIdTokenParsingException) {
        throw AuthException(AuthErrorCode.INVALID_CREDENTIALS, failure)
    } catch (failure: IllegalArgumentException) {
        throw AuthException(AuthErrorCode.INVALID_CREDENTIALS, failure)
    }
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

internal fun Throwable?.toAuthException(): AuthException {
    val mapped = mapFirebaseAuthError(
        errorCode = (this as? FirebaseAuthException)?.errorCode,
        isNetworkFailure = this is FirebaseNetworkException,
    )
    return AuthException(mapped, this)
}

internal fun mapFirebaseAuthError(
    errorCode: String?,
    isNetworkFailure: Boolean = false,
): AuthErrorCode = if (isNetworkFailure) {
    AuthErrorCode.NETWORK
} else {
    when (errorCode) {
        "ERROR_WEB_CONTEXT_CANCELED" -> AuthErrorCode.CANCELLED
        "ERROR_WEB_CONTEXT_ALREADY_PRESENTED", "ERROR_WEB_STORAGE_UNSUPPORTED" ->
            AuthErrorCode.PROVIDER_UNAVAILABLE
        "ERROR_INVALID_EMAIL" -> AuthErrorCode.INVALID_EMAIL
        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> AuthErrorCode.INVALID_CREDENTIALS
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthErrorCode.EMAIL_ALREADY_IN_USE
        "ERROR_WEAK_PASSWORD" -> AuthErrorCode.WEAK_PASSWORD
        "ERROR_USER_DISABLED" -> AuthErrorCode.USER_DISABLED
        "ERROR_TOO_MANY_REQUESTS" -> AuthErrorCode.TOO_MANY_REQUESTS
        "ERROR_NETWORK_REQUEST_FAILED" -> AuthErrorCode.NETWORK
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> AuthErrorCode.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL
        "ERROR_CREDENTIAL_ALREADY_IN_USE" -> AuthErrorCode.CREDENTIAL_ALREADY_IN_USE
        else -> AuthErrorCode.UNKNOWN
    }
}
