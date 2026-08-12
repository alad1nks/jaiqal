package com.alad1nks.jaiqal.feature.plants.presentation

import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlantHistoryChartTest {
    @Test
    fun missingMetricBreaksLineInsteadOfConnectingAcrossIt() {
        val points = listOf(
            point("2026-07-29T10:00:00Z", 10.0),
            point("2026-07-29T10:05:00Z", null),
            point("2026-07-29T10:10:00Z", 20.0),
        )

        val segments = historyChartSegments(points, HistoryInterval.FIVE_MINUTES) { it.soilMoisturePercent }

        assertEquals(listOf(1, 1), segments.map { it.size })
    }

    @Test
    fun unexpectedlyLargeTimeGapBreaksAggregatedLine() {
        val points = listOf(
            point("2026-07-29T10:00:00Z", 10.0),
            point("2026-07-29T10:05:00Z", 15.0),
            point("2026-07-29T11:00:00Z", 20.0),
        )

        val segments = historyChartSegments(points, HistoryInterval.FIVE_MINUTES) { it.soilMoisturePercent }

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    @Test
    fun realtimeReconnectDelayIsExponentialBoundedAndJittered() {
        assertEquals(1_000L, realtimeRetryDelayMillis(0, jitter = 0.0))
        assertEquals(4_000L, realtimeRetryDelayMillis(2, jitter = 0.0))
        assertEquals(30_000L, realtimeRetryDelayMillis(20, jitter = 1.0))
        assertTrue(realtimeRetryDelayMillis(3, jitter = 0.5) > 8_000L)
    }

    private fun point(time: String, value: Double?) =
        PlantHistoryPoint(measuredAt = time, soilMoisturePercent = value)
}
