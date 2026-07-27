package com.alad1nks.jaiqal.auth

class FakeFirebaseTokenVerifier(
    private val results: Map<String, Result<VerifiedFirebaseToken>>,
) : FirebaseTokenVerifier {
    val verifiedTokens = mutableListOf<String>()

    override suspend fun verify(idToken: String): VerifiedFirebaseToken {
        verifiedTokens += idToken
        return results[idToken]?.getOrThrow()
            ?: throw IllegalArgumentException("The fake verifier has no result for this token")
    }
}
