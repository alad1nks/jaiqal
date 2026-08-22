package com.alad1nks.jaiqal.core.auth

import android.os.Bundle
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class AndroidFirebaseAuthProviderTest {
    @Test
    fun restoresFirebaseSessionAndForwardsIdTokenRefresh() = runTest {
        val bridge = FakeBridge(AndroidFirebaseUser("owner@example.com", emailVerified = true))
        val provider = AndroidFirebaseAuthProvider.fromBridge(bridge)

        assertEquals(AuthState.Authenticated("owner@example.com", true), provider.authState.value)
        assertEquals("fresh-token", provider.getIdToken(forceRefresh = true))
        assertEquals(listOf(true), bridge.tokenRequests)
    }

    @Test
    fun reloadAndLogoutUpdatePublishedStateWithoutRealFirebase() = runTest {
        val bridge = FakeBridge(AndroidFirebaseUser("owner@example.com", emailVerified = false))
        val provider = AndroidFirebaseAuthProvider.fromBridge(bridge)
        bridge.user = AndroidFirebaseUser("owner@example.com", emailVerified = true)

        provider.reloadUser()
        assertEquals(AuthState.Authenticated("owner@example.com", true), provider.authState.value)
        provider.signOut()

        assertEquals(AuthState.Unauthenticated, provider.authState.value)
        assertNull(bridge.user)
    }

    @Test
    fun federatedSignInIsForwardedWithoutProviderTokens() = runTest {
        val bridge = FakeBridge(null)
        val provider = AndroidFirebaseAuthProvider.fromBridge(bridge)

        provider.signIn(FederatedAuthMethod.GOOGLE)

        assertEquals(FederatedAuthMethod.GOOGLE, bridge.federatedAuthMethod)
    }

    @Test
    fun googleSignInUsesAuthorizedAccountsFirst() = runTest {
        val source = FakeGoogleIdTokenSource { "authorized-token" }
        val authenticator = FakeGoogleFirebaseAuthenticator()
        val coordinator = AndroidGoogleSignInCoordinator(source, authenticator)

        coordinator.signIn()

        assertEquals(listOf(true), source.requests)
        assertEquals(listOf("authorized-token"), authenticator.idTokens)
    }

    @Test
    fun googleSignInRetriesWithAllAccountsWhenNoAuthorizedCredentialExists() = runTest {
        val source = FakeGoogleIdTokenSource { authorizedOnly ->
            if (authorizedOnly) throw NoGoogleCredentialAvailableException()
            "new-account-token"
        }
        val authenticator = FakeGoogleFirebaseAuthenticator()
        val coordinator = AndroidGoogleSignInCoordinator(source, authenticator)

        coordinator.signIn()

        assertEquals(listOf(true, false), source.requests)
        assertEquals(listOf("new-account-token"), authenticator.idTokens)
    }

    @Test
    fun googleSignInMapsMissingCredentialsAfterFallbackToProviderUnavailable() = runTest {
        val source = FakeGoogleIdTokenSource { throw NoGoogleCredentialAvailableException() }
        val authenticator = FakeGoogleFirebaseAuthenticator()
        val coordinator = AndroidGoogleSignInCoordinator(source, authenticator)

        val failure = assertFailsWith<AuthException> { coordinator.signIn() }

        assertEquals(AuthErrorCode.PROVIDER_UNAVAILABLE, failure.code)
        assertEquals(listOf(true, false), source.requests)
        assertEquals(emptyList(), authenticator.idTokens)
    }

    @Test
    fun googleSignInDoesNotRetryUserCancellation() = runTest {
        val source = FakeGoogleIdTokenSource {
            throw AuthException(AuthErrorCode.CANCELLED)
        }
        val authenticator = FakeGoogleFirebaseAuthenticator()
        val coordinator = AndroidGoogleSignInCoordinator(source, authenticator)

        val failure = assertFailsWith<AuthException> { coordinator.signIn() }

        assertEquals(AuthErrorCode.CANCELLED, failure.code)
        assertEquals(listOf(true), source.requests)
        assertEquals(emptyList(), authenticator.idTokens)
    }

    @Test
    fun credentialManagerErrorsMapToStableAuthErrors() {
        val mappings = listOf(
            GetCredentialCancellationException() to AuthErrorCode.CANCELLED,
            GetCredentialProviderConfigurationException() to AuthErrorCode.PROVIDER_UNAVAILABLE,
            GetCredentialUnsupportedException() to AuthErrorCode.PROVIDER_UNAVAILABLE,
            GetCredentialUnknownException() to AuthErrorCode.UNKNOWN,
        )

        mappings.forEach { (failure, expected) ->
            assertEquals(expected, failure.toGoogleAuthException().code)
        }
    }

    @Test
    fun unexpectedCredentialTypeMapsToInvalidCredentials() {
        val failure = assertFailsWith<AuthException> {
            CustomCredential("unexpected-credential", Bundle()).toGoogleIdToken()
        }

        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, failure.code)
    }

    @Test
    fun firebaseNetworkFailureMapsToNetworkError() {
        assertEquals(
            AuthErrorCode.NETWORK,
            mapFirebaseAuthError(errorCode = null, isNetworkFailure = true),
        )
    }

    @Test
    fun appleSignInCompletesPendingResultBeforeStartingNewFlow() = runTest {
        val client = FakeAppleAuthClient(pendingResultAvailable = true)
        val coordinator = AndroidAppleSignInCoordinator(client)

        coordinator.signIn()

        assertEquals(1, client.pendingChecks)
        assertEquals(0, client.startedFlows)
    }

    @Test
    fun appleSignInStartsNewFlowWhenThereIsNoPendingResult() = runTest {
        val client = FakeAppleAuthClient(pendingResultAvailable = false)
        val coordinator = AndroidAppleSignInCoordinator(client)

        coordinator.signIn()

        assertEquals(1, client.pendingChecks)
        assertEquals(1, client.startedFlows)
    }

    @Test
    fun concurrentAppleSignInCallsShareSingleFlow() = runTest {
        val flowMayFinish = CompletableDeferred<Unit>()
        val flowStarted = CompletableDeferred<Unit>()
        val client = FakeAppleAuthClient(
            pendingResultAvailable = false,
            startAction = {
                flowStarted.complete(Unit)
                flowMayFinish.await()
            },
        )
        val coordinator = AndroidAppleSignInCoordinator(client)

        val first = async { coordinator.signIn() }
        flowStarted.await()
        val second = async { coordinator.signIn() }
        flowMayFinish.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, client.pendingChecks)
        assertEquals(1, client.startedFlows)
    }

    @Test
    fun appleWebErrorsMapToStableAuthErrors() {
        val mappings = mapOf(
            "ERROR_WEB_CONTEXT_CANCELED" to AuthErrorCode.CANCELLED,
            "ERROR_WEB_CONTEXT_ALREADY_PRESENTED" to AuthErrorCode.PROVIDER_UNAVAILABLE,
            "ERROR_WEB_STORAGE_UNSUPPORTED" to AuthErrorCode.PROVIDER_UNAVAILABLE,
            "ERROR_WEB_INTERNAL_ERROR" to AuthErrorCode.UNKNOWN,
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" to
                AuthErrorCode.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
            "ERROR_CREDENTIAL_ALREADY_IN_USE" to AuthErrorCode.CREDENTIAL_ALREADY_IN_USE,
        )

        mappings.forEach { (errorCode, expected) ->
            assertEquals(expected, mapFirebaseAuthError(errorCode))
        }
    }

    @Test
    fun signOutClearsFirebaseBeforeCredentialManagerState() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = AndroidSignOutCoordinator(
            firebaseSignOut = { calls += "firebase" },
            credentialStateCleaner = AndroidCredentialStateCleaner { calls += "credentials" },
        )

        coordinator.signOut()

        assertEquals(listOf("firebase", "credentials"), calls)
    }

    @Test
    fun credentialStateCleanupFailureDoesNotRestoreFirebaseSession() = runTest {
        var firebaseSignedOut = false
        val coordinator = AndroidSignOutCoordinator(
            firebaseSignOut = { firebaseSignedOut = true },
            credentialStateCleaner = AndroidCredentialStateCleaner { error("provider unavailable") },
        )

        coordinator.signOut()

        assertEquals(true, firebaseSignedOut)
    }

    @Test
    fun cancelledCredentialCleanupStillPublishesUnauthenticatedState() = runTest {
        val bridge = FakeBridge(AndroidFirebaseUser("owner@example.com", emailVerified = true)).apply {
            signOutFailure = CancellationException("cancelled")
        }
        val provider = AndroidFirebaseAuthProvider.fromBridge(bridge)

        assertFailsWith<CancellationException> { provider.signOut() }

        assertEquals(AuthState.Unauthenticated, provider.authState.value)
        assertNull(bridge.user)
    }

    private class FakeBridge(initialUser: AndroidFirebaseUser?) : AndroidFirebaseAuthBridge {
        var user = initialUser
        val tokenRequests = mutableListOf<Boolean>()
        var federatedAuthMethod: FederatedAuthMethod? = null
        var signOutFailure: Throwable? = null

        override fun addAuthStateListener(listener: (AndroidFirebaseUser?) -> Unit): AndroidAuthStateSubscription {
            listener(user)
            return object : AndroidAuthStateSubscription {}
        }

        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signIn(method: FederatedAuthMethod) {
            federatedAuthMethod = method
        }
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun sendEmailVerification() = Unit
        override suspend fun reloadUser() = user
        override suspend fun getIdToken(forceRefresh: Boolean): String {
            tokenRequests += forceRefresh
            return if (forceRefresh) "fresh-token" else "cached-token"
        }
        override suspend fun signOut() {
            user = null
            signOutFailure?.let { throw it }
        }
    }

    private class FakeGoogleIdTokenSource(
        private val response: (filterByAuthorizedAccounts: Boolean) -> String,
    ) : AndroidGoogleIdTokenSource {
        val requests = mutableListOf<Boolean>()

        override suspend fun getIdToken(filterByAuthorizedAccounts: Boolean): String {
            requests += filterByAuthorizedAccounts
            return response(filterByAuthorizedAccounts)
        }
    }

    private class FakeGoogleFirebaseAuthenticator : AndroidGoogleFirebaseAuthenticator {
        val idTokens = mutableListOf<String>()

        override suspend fun signIn(idToken: String) {
            idTokens += idToken
        }
    }

    private class FakeAppleAuthClient(
        private var pendingResultAvailable: Boolean,
        private val startAction: suspend () -> Unit = {},
    ) : AndroidAppleAuthClient {
        var pendingChecks = 0
        var startedFlows = 0

        override suspend fun completePendingSignIn(): Boolean {
            pendingChecks += 1
            return pendingResultAvailable.also { pendingResultAvailable = false }
        }

        override suspend fun startSignIn() {
            startedFlows += 1
            startAction()
        }
    }
}
