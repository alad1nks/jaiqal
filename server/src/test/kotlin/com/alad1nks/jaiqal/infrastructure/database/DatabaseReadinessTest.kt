package com.alad1nks.jaiqal.infrastructure.database

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseReadinessTest {
    @Test
    fun `concurrent callers share one database check per cache window`() = runBlocking {
        val checks = AtomicInteger()
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val readiness = CachedDatabaseReadiness(
            delegate = {
                checks.incrementAndGet()
                checkStarted.complete(Unit)
                releaseCheck.await()
                true
            },
            cacheTtlMilliseconds = 1_000,
        )

        val results = (0 until 32).map {
            async(Dispatchers.Default) { readiness.isReady() }
        }
        checkStarted.await()
        assertEquals(1, checks.get())
        releaseCheck.complete(Unit)

        assertTrue(results.awaitAll().all { it })
        assertEquals(1, checks.get())
    }

    @Test
    fun `ready and unavailable results expire without serving stale success`() = runBlocking {
        val nowNanos = AtomicLong()
        val checks = AtomicInteger()
        val readiness = CachedDatabaseReadiness(
            delegate = { checks.incrementAndGet() == 1 },
            cacheTtlMilliseconds = 100,
            monotonicNanos = nowNanos::get,
        )

        assertTrue(readiness.isReady())
        nowNanos.set(99_000_000)
        assertTrue(readiness.isReady())
        assertEquals(1, checks.get())

        nowNanos.set(100_000_000)
        assertFalse(readiness.isReady())
        assertEquals(2, checks.get())
        nowNanos.set(199_000_000)
        assertFalse(readiness.isReady())
        assertEquals(2, checks.get())

        nowNanos.set(200_000_000)
        assertFalse(readiness.isReady())
        assertEquals(3, checks.get())
    }
}
