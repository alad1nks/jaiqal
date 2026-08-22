package com.alad1nks.jaiqal.core.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
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
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.OAuthCredential
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class AndroidFirebaseUser(
    val email: String?,
    val emailVerified: Boolean,
    val method: AccountAuthMethod = AccountAuthMethod.UNKNOWN,
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
    private val firebaseReauthenticator: AndroidGoogleFirebaseAuthenticator = firebaseAuthenticator,
) {
    suspend fun signIn() {
        firebaseAuthenticator.signIn(requestIdToken())
    }

    suspend fun reauthenticate() {
        firebaseReauthenticator.signIn(requestIdToken())
    }

    private suspend fun requestIdToken(): String {
        val idToken = try {
            idTokenSource.getIdToken(filterByAuthorizedAccounts = true)
        } catch (_: NoGoogleCredentialAvailableException) {
            try {
                idTokenSource.getIdToken(filterByAuthorizedAccounts = false)
            } catch (failure: NoGoogleCredentialAvailableException) {
                throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, failure)
            }
        }
        return idToken
    }
}

internal interface AndroidAppleAuthClient {
    suspend fun completePendingSignIn(): Boolean
    suspend fun startSignIn()
    suspend fun startReauthentication() = startSignIn()
}

internal class AndroidAppleSignInCoordinator(
    private val client: AndroidAppleAuthClient,
) {
    private val operationLock = Any()
    private var activeOperation: CompletableDeferred<Unit>? = null

    suspend fun signIn() {
        runOperation {
            if (!client.completePendingSignIn()) client.startSignIn()
        }
    }

    suspend fun reauthenticate() {
        runOperation(client::startReauthentication)
    }

    private suspend fun runOperation(action: suspend () -> Unit) {
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
            action()
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

internal fun interface AndroidCredentialStateCleaner {
    suspend fun clear()
}

internal class AndroidSignOutCoordinator(
    private val firebaseSignOut: () -> Unit,
    private val credentialStateCleaner: AndroidCredentialStateCleaner?,
) {
    suspend fun signOut() {
        firebaseSignOut()
        try {
            credentialStateCleaner?.clear()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Firebase is already signed out. Credential state cleanup is best-effort and carries no user data.
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
    suspend fun signOut()
    suspend fun reauthenticateForAccountDeletion(password: String?): Unit =
        throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
    suspend fun deleteCurrentUser(): Unit = throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
}

class AndroidFirebaseAuthProvider private constructor(
    private val bridge: AndroidFirebaseAuthBridge,
) : AuthProvider {
    constructor(firebaseAuth: FirebaseAuth) : this(
        FirebaseSdkAuthBridge(
            firebaseAuth = firebaseAuth,
            googleSignIn = null,
            appleSignIn = null,
            credentialStateCleaner = null,
        ),
    )

    constructor(
        firebaseAuth: FirebaseAuth,
        context: Context,
        activity: Activity,
        googleServerClientId: String?,
    ) : this(createFirebaseSdkAuthBridge(firebaseAuth, context, activity, googleServerClientId))

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
        try {
            bridge.signOut()
        } finally {
            mutableAuthState.value = AuthState.Unauthenticated
        }
    }

    override suspend fun reauthenticateForAccountDeletion(password: String?) =
        bridge.reauthenticateForAccountDeletion(password)

    override suspend fun deleteCurrentUser() {
        bridge.deleteCurrentUser()
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
    credentialStateCleaner: AndroidCredentialStateCleaner?,
) : AndroidFirebaseAuthBridge {
    private val signOutCoordinator = AndroidSignOutCoordinator(
        firebaseSignOut = firebaseAuth::signOut,
        credentialStateCleaner = credentialStateCleaner,
    )

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

    override suspend fun signOut() = signOutCoordinator.signOut()

    override suspend fun reauthenticateForAccountDeletion(password: String?) {
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        when (user.accountAuthMethod()) {
            AccountAuthMethod.PASSWORD -> {
                val email = user.email ?: throw AuthException(AuthErrorCode.INVALID_CREDENTIALS)
                val suppliedPassword = password?.takeIf(String::isNotBlank)
                    ?: throw AuthException(AuthErrorCode.INVALID_CREDENTIALS)
                user.reauthenticate(EmailAuthProvider.getCredential(email, suppliedPassword)).awaitResult()
            }
            AccountAuthMethod.GOOGLE -> googleSignIn?.reauthenticate()
                ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
            AccountAuthMethod.APPLE -> appleSignIn?.reauthenticate()
                ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
            AccountAuthMethod.UNKNOWN -> throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
        }
    }

    override suspend fun deleteCurrentUser() {
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        user.delete().awaitResult()
    }
}

private fun createFirebaseSdkAuthBridge(
    firebaseAuth: FirebaseAuth,
    context: Context,
    activity: Activity,
    googleServerClientId: String?,
): AndroidFirebaseAuthBridge {
    val credentialManager = CredentialManager.create(context.applicationContext)
    return FirebaseSdkAuthBridge(
        firebaseAuth = firebaseAuth,
        googleSignIn = googleServerClientId?.takeIf(String::isNotBlank)?.let { clientId ->
            createGoogleSignInCoordinator(firebaseAuth, credentialManager, activity, clientId)
        },
        appleSignIn = createAppleSignInCoordinator(firebaseAuth, activity),
        credentialStateCleaner = AndroidCredentialStateCleaner {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        },
    )
}

private fun createGoogleSignInCoordinator(
    firebaseAuth: FirebaseAuth,
    credentialManager: CredentialManager,
    activity: Activity,
    googleServerClientId: String,
): AndroidGoogleSignInCoordinator = AndroidGoogleSignInCoordinator(
    idTokenSource = CredentialManagerGoogleIdTokenSource(
        credentialManager = credentialManager,
        activity = activity,
        googleServerClientId = googleServerClientId,
    ),
    firebaseAuthenticator = AndroidGoogleFirebaseAuthenticator { idToken ->
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential).awaitResult()
    },
    firebaseReauthenticator = AndroidGoogleFirebaseAuthenticator { idToken ->
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).awaitResult()
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

    override suspend fun startReauthentication() {
        val activity = activityReference.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: throw AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE)
        val provider = OAuthProvider.newBuilder("apple.com")
            .setScopes(listOf("email", "name"))
            .build()
        val user = firebaseAuth.currentUser ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)
        val result = user.startActivityForReauthenticateWithProvider(activity, provider).awaitResult()
        val freshAppleToken = (result.credential as? OAuthCredential)?.accessToken
            ?.takeIf(String::isNotBlank)
            ?: throw AuthException(AuthErrorCode.INVALID_CREDENTIALS)
        firebaseAuth.revokeAccessToken(freshAppleToken).awaitResult()
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
    AndroidFirebaseUser(email = it.email, emailVerified = it.isEmailVerified, method = it.accountAuthMethod())
}

private fun FirebaseUser.accountAuthMethod(): AccountAuthMethod {
    val providers = providerData.map { it.providerId }.toSet()
    return when {
        EmailAuthProvider.PROVIDER_ID in providers -> AccountAuthMethod.PASSWORD
        GoogleAuthProvider.PROVIDER_ID in providers -> AccountAuthMethod.GOOGLE
        "apple.com" in providers -> AccountAuthMethod.APPLE
        else -> AccountAuthMethod.UNKNOWN
    }
}

private fun AndroidFirebaseUser?.toAuthState(): AuthState = if (this == null) {
    AuthState.Unauthenticated
} else {
    AuthState.Authenticated(email = email, emailVerified = emailVerified, method = method)
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
        "ERROR_REQUIRES_RECENT_LOGIN" -> AuthErrorCode.REAUTHENTICATION_REQUIRED
        else -> AuthErrorCode.UNKNOWN
    }
}
