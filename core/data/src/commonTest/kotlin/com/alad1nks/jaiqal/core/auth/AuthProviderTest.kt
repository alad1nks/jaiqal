package com.alad1nks.jaiqal.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class AuthProviderTest {
    @Test
    fun unavailableProviderReportsFederatedProviderUnavailable() = runTest {
        val failure = assertFailsWith<AuthException> {
            UnavailableAuthProvider().signIn(FederatedAuthMethod.GOOGLE)
        }

        assertEquals(AuthErrorCode.PROVIDER_UNAVAILABLE, failure.code)
    }
}
