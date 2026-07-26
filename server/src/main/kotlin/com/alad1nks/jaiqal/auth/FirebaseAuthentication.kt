package com.alad1nks.jaiqal.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class VerifiedFirebaseToken(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
)

fun interface FirebaseTokenVerifier {
    suspend fun verify(idToken: String): VerifiedFirebaseToken
}

data class UserPrincipal(
    val userId: UUID,
    val firebaseUid: String,
    val email: String?,
    val emailVerified: Boolean,
)

fun interface FirebaseIdentityRepository {
    suspend fun resolve(token: VerifiedFirebaseToken, autoProvision: Boolean): UUID?
}

class FirebaseUserAuthenticator(
    private val verifier: FirebaseTokenVerifier,
    private val identities: FirebaseIdentityRepository,
    private val autoProvisionUsers: Boolean,
) {
    suspend fun authenticate(idToken: String): UserPrincipal? = runCatching {
        val verified = verifier.verify(idToken)
        require(verified.uid.isNotBlank())
        identities.resolve(verified, autoProvisionUsers)?.let { userId ->
            UserPrincipal(userId, verified.uid, verified.email, verified.emailVerified)
        }
    }.getOrNull()
}

class FirebaseAdminTokenVerifier private constructor(
    private val auth: FirebaseAuth,
    private val checkRevokedTokens: Boolean,
) : FirebaseTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedFirebaseToken = withContext(Dispatchers.IO) {
        val decoded = auth.verifyIdToken(idToken, checkRevokedTokens)
        VerifiedFirebaseToken(decoded.uid, decoded.email, decoded.isEmailVerified)
    }

    companion object {
        fun initialize(projectId: String, checkRevokedTokens: Boolean): FirebaseAdminTokenVerifier {
            val app = synchronized(FirebaseAdminTokenVerifier::class.java) {
                FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(
                        FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.getApplicationDefault())
                            .setProjectId(projectId)
                            .build(),
                    )
            }
            return FirebaseAdminTokenVerifier(FirebaseAuth.getInstance(app), checkRevokedTokens)
        }
    }
}
