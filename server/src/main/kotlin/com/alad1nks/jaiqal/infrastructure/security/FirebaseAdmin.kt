package com.alad1nks.jaiqal.infrastructure.security

import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FirebaseAdminTokenVerifier(
    private val firebaseAuth: FirebaseAuth,
    private val checkRevokedTokens: Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FirebaseTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedFirebaseToken = withContext(dispatcher) {
        val decoded = try {
            firebaseAuth.verifyIdToken(idToken, checkRevokedTokens)
        } catch (failure: FirebaseAuthException) {
            throw FirebaseTokenVerificationException(failure)
        } catch (failure: IllegalArgumentException) {
            throw FirebaseTokenVerificationException(failure)
        }
        VerifiedFirebaseToken(
            uid = decoded.uid,
            email = decoded.email,
            emailVerified = decoded.isEmailVerified,
        )
    }
}

internal class FirebaseAdminInitializer(
    private val factory: (FirebaseConfig) -> FirebaseTokenVerifier,
) {
    @Volatile
    private var initialized: FirebaseTokenVerifier? = null

    fun initialize(config: FirebaseConfig): FirebaseTokenVerifier {
        initialized?.let { return it }
        return synchronized(this) {
            initialized ?: factory(config).also { initialized = it }
        }
    }
}

object FirebaseAdmin {
    private val initializer = FirebaseAdminInitializer(::createVerifier)

    fun initialize(config: FirebaseConfig): FirebaseTokenVerifier = initializer.initialize(config)

    private fun createVerifier(config: FirebaseConfig): FirebaseTokenVerifier {
        val credentials = try {
            GoogleCredentials.getApplicationDefault()
        } catch (_: Exception) {
            throw IllegalStateException(
                "Firebase Admin credentials are unavailable. Configure Application Default Credentials " +
                    "using workload identity or GOOGLE_APPLICATION_CREDENTIALS.",
            )
        }

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setProjectId(config.projectId)
            .build()

        val app = try {
            FirebaseApp.initializeApp(options, FIREBASE_APP_NAME)
        } catch (_: Exception) {
            throw IllegalStateException(
                "Firebase Admin initialization failed for the configured FIREBASE_PROJECT_ID.",
            )
        }
        return FirebaseAdminTokenVerifier(
            firebaseAuth = FirebaseAuth.getInstance(app),
            checkRevokedTokens = config.checkRevokedTokens,
        )
    }

    private const val FIREBASE_APP_NAME = "jaiqal-auth"
}
