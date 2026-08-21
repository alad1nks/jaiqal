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

    private class FakeBridge(initialUser: AndroidFirebaseUser?) : AndroidFirebaseAuthBridge {
        var user = initialUser
        val tokenRequests = mutableListOf<Boolean>()
        var federatedAuthMethod: FederatedAuthMethod? = null

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
        override fun signOut() { user = null }
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
}
