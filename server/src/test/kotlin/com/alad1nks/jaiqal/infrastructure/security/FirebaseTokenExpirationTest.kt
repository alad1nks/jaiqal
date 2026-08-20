package com.alad1nks.jaiqal.infrastructure.security

import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirebaseTokenExpirationTest {
    @Test
    fun `reads expiration from verified Firebase claims`() {
        assertEquals(
            Instant.parse("2026-08-16T12:00:00Z"),
            firebaseTokenExpiration(mapOf("exp" to 1_786_881_600L)),
        )
    }

    @Test
    fun `rejects missing or malformed expiration claim`() {
        assertFailsWith<FirebaseTokenVerificationException> {
            firebaseTokenExpiration(emptyMap())
        }
        assertFailsWith<FirebaseTokenVerificationException> {
            firebaseTokenExpiration(mapOf("exp" to "never"))
        }
    }
}
