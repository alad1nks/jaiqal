package com.alad1nks.jaiqal.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    private class FakeBridge(initialUser: IosFirebaseUser?) : IosFirebaseAuthBridge {
        var user = initialUser
        val tokenRequests = mutableListOf<Boolean>()

        override fun addAuthStateListener(listener: (IosFirebaseUser?) -> Unit): IosAuthStateSubscription {
            listener(user)
            return object : IosAuthStateSubscription { override fun cancel() = Unit }
        }

        override fun signUp(email: String, password: String, completion: (String?) -> Unit) = completion(null)
        override fun signIn(email: String, password: String, completion: (String?) -> Unit) = completion(null)
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
