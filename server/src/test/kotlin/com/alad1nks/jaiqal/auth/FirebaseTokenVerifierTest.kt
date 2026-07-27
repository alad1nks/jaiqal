package com.alad1nks.jaiqal.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirebaseTokenVerifierTest {
    @Test
    fun fakeReturnsConfiguredTokenAndRecordsInvocation() = runBlocking {
        val expected = VerifiedFirebaseToken(
            uid = "firebase-user-123",
            email = "user@example.test",
            emailVerified = true,
        )
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("valid-id-token" to Result.success(expected)),
        )

        assertEquals(expected, verifier.verify("valid-id-token"))
        assertEquals(listOf("valid-id-token"), verifier.verifiedTokens)
    }

    @Test
    fun fakePropagatesConfiguredVerificationFailure() = runBlocking {
        val failure = IllegalStateException("expired")
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("expired-id-token" to Result.failure(failure)),
        )

        assertEquals(
            failure,
            assertFailsWith<IllegalStateException> { verifier.verify("expired-id-token") },
        )
    }

    @Test
    fun verifiedTokenRequiresUid() {
        assertFailsWith<IllegalArgumentException> {
            VerifiedFirebaseToken(uid = " ", email = null, emailVerified = false)
        }
    }
}
