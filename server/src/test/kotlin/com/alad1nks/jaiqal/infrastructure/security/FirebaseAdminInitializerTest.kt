package com.alad1nks.jaiqal.infrastructure.security

import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class FirebaseAdminInitializerTest {
    @Test
    fun initializesVerifierExactlyOnceUnderConcurrentAccess() = runBlocking {
        val creations = AtomicInteger()
        val expected = FirebaseTokenVerifier {
            VerifiedFirebaseToken("uid", null, false, Instant.parse("2100-01-01T00:00:00Z"))
        }
        val initializer = FirebaseAdminInitializer {
            creations.incrementAndGet()
            Thread.sleep(20)
            expected
        }
        val config = FirebaseConfig("test-project")

        val initialized = List(20) {
            async(Dispatchers.Default) { initializer.initialize(config) }
        }.awaitAll()

        assertEquals(1, creations.get())
        initialized.forEach { assertSame(expected, it) }
    }

    @Test
    fun firebaseAuthInitializesWithoutExcludedStorageRuntime() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.google.cloud.storage.StorageOptions")
        }
        assertNotNull(Class.forName("com.google.cloud.firestore.FirestoreOptions"))

        val credentials = GoogleCredentials.create(
            AccessToken("test-only-token", Date.from(Instant.parse("2100-01-01T00:00:00Z"))),
        )
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setProjectId("firebase-auth-compatibility-test")
            .build()
        val app = FirebaseApp.initializeApp(
            options,
            "firebase-auth-compatibility-${System.nanoTime()}",
        )

        try {
            assertNotNull(FirebaseAuth.getInstance(app))
        } finally {
            app.delete()
        }
    }
}
