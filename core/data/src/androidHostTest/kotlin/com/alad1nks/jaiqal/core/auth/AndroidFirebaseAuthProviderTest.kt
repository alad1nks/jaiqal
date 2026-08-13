package com.alad1nks.jaiqal.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private class FakeBridge(initialUser: AndroidFirebaseUser?) : AndroidFirebaseAuthBridge {
        var user = initialUser
        val tokenRequests = mutableListOf<Boolean>()

        override fun addAuthStateListener(listener: (AndroidFirebaseUser?) -> Unit): AndroidAuthStateSubscription {
            listener(user)
            return object : AndroidAuthStateSubscription {}
        }

        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun sendEmailVerification() = Unit
        override suspend fun reloadUser() = user
        override suspend fun getIdToken(forceRefresh: Boolean): String {
            tokenRequests += forceRefresh
            return if (forceRefresh) "fresh-token" else "cached-token"
        }
        override fun signOut() { user = null }
    }
}
