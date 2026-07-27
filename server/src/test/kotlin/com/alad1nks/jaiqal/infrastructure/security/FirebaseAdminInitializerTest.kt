package com.alad1nks.jaiqal.infrastructure.security

import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.config.FirebaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FirebaseAdminInitializerTest {
    @Test
    fun initializesVerifierExactlyOnceUnderConcurrentAccess() = runBlocking {
        val creations = AtomicInteger()
        val expected = FirebaseTokenVerifier {
            VerifiedFirebaseToken("uid", null, false)
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
}
