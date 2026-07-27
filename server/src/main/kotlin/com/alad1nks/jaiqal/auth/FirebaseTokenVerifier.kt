package com.alad1nks.jaiqal.auth

data class VerifiedFirebaseToken(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
) {
    init {
        require(uid.isNotBlank()) { "Firebase UID must not be blank" }
    }
}

fun interface FirebaseTokenVerifier {
    suspend fun verify(idToken: String): VerifiedFirebaseToken
}

class FirebaseTokenVerificationException(cause: Throwable? = null) : RuntimeException(
    "Firebase ID token verification failed",
    cause,
)
