package com.alad1nks.jaiqal.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class IosFirebaseAuthProviderTest {
    @Test
    fun restoresSessionAndForwardsForcedIdTokenRefresh() = runTest {
        val bridge = FakeBridge(IosFirebaseUser("owner@example.com", emailVerified = true))
        val provider = IosFirebaseAuthProvider(bridge)

        assertEquals(AuthState.Authenticated("owner@example.com", true), provider.authState.value)
        assertEquals("fresh-token", provider.getIdToken(forceRefresh = true))
        assertEquals(listOf(true), bridge.tokenRequests)
    }

    @Test
    fun reloadAndLogoutUpdatePublishedState() = runTest {
        val bridge = FakeBridge(IosFirebaseUser("owner@example.com", emailVerified = false))
        val provider = IosFirebaseAuthProvider(bridge)
        bridge.user = IosFirebaseUser("owner@example.com", emailVerified = true)

        provider.reloadUser()
        assertEquals(AuthState.Authenticated("owner@example.com", true), provider.authState.value)
        provider.signOut()

        assertEquals(AuthState.Unauthenticated, provider.authState.value)
        assertNull(bridge.user)
    }

    @Test
    fun federatedSignInIsForwardedWithoutProviderTokens() = runTest {
        val bridge = FakeBridge(null)
        val provider = IosFirebaseAuthProvider(bridge)

        provider.signIn(FederatedAuthMethod.APPLE)

        assertEquals(FederatedAuthMethod.APPLE, bridge.federatedAuthMethod)
    }

    @Test
    fun federatedBridgeErrorsMapToStableAuthErrors() = runTest {
        val mappings = mapOf(
            "cancelled" to AuthErrorCode.CANCELLED,
            "provider-unavailable" to AuthErrorCode.PROVIDER_UNAVAILABLE,
            "account-exists-with-different-credential" to AuthErrorCode.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
            "credential-already-in-use" to AuthErrorCode.CREDENTIAL_ALREADY_IN_USE,
            "invalid-nonce" to AuthErrorCode.INVALID_NONCE,
        )

        mappings.forEach { (bridgeCode, expected) ->
            val provider = IosFirebaseAuthProvider(FakeBridge(null).apply { federatedError = bridgeCode })
            val failure = assertFailsWith<AuthException> { provider.signIn(FederatedAuthMethod.APPLE) }
            assertEquals(expected, failure.code)
        }
    }

    private class FakeBridge(initialUser: IosFirebaseUser?) : IosFirebaseAuthBridge {
        var user = initialUser
        val tokenRequests = mutableListOf<Boolean>()
        var federatedAuthMethod: FederatedAuthMethod? = null
        var federatedError: String? = null

        override fun addAuthStateListener(listener: (IosFirebaseUser?) -> Unit): IosAuthStateSubscription {
            listener(user)
            return object : IosAuthStateSubscription { override fun cancel() = Unit }
        }

        override fun signUp(email: String, password: String, completion: (String?) -> Unit) = completion(null)
        override fun signIn(email: String, password: String, completion: (String?) -> Unit) = completion(null)
        override fun signIn(method: FederatedAuthMethod, completion: (String?) -> Unit) {
            federatedAuthMethod = method
            completion(federatedError)
        }
        override fun sendPasswordReset(email: String, completion: (String?) -> Unit) = completion(null)
        override fun sendEmailVerification(completion: (String?) -> Unit) = completion(null)
        override fun reloadUser(completion: (IosFirebaseUser?, String?) -> Unit) = completion(user, null)
        override fun getIdToken(forceRefresh: Boolean, completion: (String?, String?) -> Unit) {
            tokenRequests += forceRefresh
            completion(if (forceRefresh) "fresh-token" else "cached-token", null)
        }
        override fun signOut(): String? { user = null; return null }
    }
}
