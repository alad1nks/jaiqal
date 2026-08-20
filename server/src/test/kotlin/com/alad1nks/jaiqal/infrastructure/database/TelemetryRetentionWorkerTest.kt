package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.TelemetryRetentionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelemetryRetentionWorkerTest {
    @Test
    fun `stops after a partial batch`() {
        val batches = ArrayDeque(listOf(2, 2, 1))
        val config = TelemetryRetentionConfig(batchSize = 2, maxBatchesPerRun = 10)

        val result = runTelemetryRetentionBatches(config) { batches.removeFirst() }

        assertEquals(TelemetryRetentionResult(5, moreEligibleRowsPossible = false), result)
        assertEquals(0, batches.size)
    }

    @Test
    fun `caps each cycle and reports possible backlog`() {
        val config = TelemetryRetentionConfig(batchSize = 2, maxBatchesPerRun = 2)
        var calls = 0

        val result = runTelemetryRetentionBatches(config) { calls += 1; 2 }

        assertEquals(TelemetryRetentionResult(4, moreEligibleRowsPossible = true), result)
        assertEquals(2, calls)
    }

    @Test
    fun `rejects an invalid database update count`() {
        val config = TelemetryRetentionConfig(batchSize = 2, maxBatchesPerRun = 2)

        assertFailsWith<IllegalArgumentException> {
            runTelemetryRetentionBatches(config) { 3 }
        }
    }
}
