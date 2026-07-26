package com.alad1nks.jaiqal.auth

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirebaseAuthenticationTest {
    @Test fun `verified claims and internal UUID become principal`() = runBlocking {
        val userId = UUID.randomUUID()
        val authenticator = FirebaseUserAuthenticator(
            FirebaseTokenVerifier { VerifiedFirebaseToken("firebase-uid", "user@example.com", true) },
            FirebaseIdentityRepository { token, auto -> assertEquals("firebase-uid", token.uid); assertEquals(false, auto); userId },
            false,
        )
        val principal = authenticator.authenticate("valid-token")!!
        assertEquals(userId, principal.userId)
        assertEquals("firebase-uid", principal.firebaseUid)
        assertEquals("user@example.com", principal.email)
        assertEquals(true, principal.emailVerified)
    }

    @Test fun `invalid token and unknown identity are rejected`() = runBlocking {
        val invalid = FirebaseUserAuthenticator(FirebaseTokenVerifier { error("expired") }, FirebaseIdentityRepository { _, _ -> error("unused") }, false)
        val unknown = FirebaseUserAuthenticator(FirebaseTokenVerifier { VerifiedFirebaseToken("unknown", null, false) }, FirebaseIdentityRepository { _, auto -> assertEquals(false, auto); null }, false)
        assertNull(invalid.authenticate("bad-token"))
        assertNull(unknown.authenticate("valid-token"))
    }

    @Test fun `auto provisioning policy is passed to identity repository`() = runBlocking {
        val userId = UUID.randomUUID()
        val authenticator = FirebaseUserAuthenticator(
            FirebaseTokenVerifier { VerifiedFirebaseToken("new-user", null, false) },
            FirebaseIdentityRepository { _, auto -> assertEquals(true, auto); userId },
            true,
        )
        assertEquals(userId, authenticator.authenticate("valid")?.userId)
    }
}
