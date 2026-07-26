package com.alad1nks.jaiqal.telemetry

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.config.HistoryConfig
import com.alad1nks.jaiqal.users.UserApiException
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class PlantTelemetryServiceTest {
    private val userId = UUID.randomUUID()
    private val plantId = UUID.randomUUID()
    private var captured: HistoryRequest? = null
    private val repository = object : PlantTelemetryRepository {
        override fun latest(userId: UUID, plantId: UUID) = null
        override fun history(userId: UUID, plantId: UUID, request: HistoryRequest, limit: Int): List<PlantHistoryPoint>? {
            captured = request
            assertEquals(25, limit)
            return emptyList()
        }
        override fun ownsPlant(userId: UUID, plantId: UUID) = userId == this@PlantTelemetryServiceTest.userId && plantId == this@PlantTelemetryServiceTest.plantId
    }
    private val service = PlantTelemetryService(repository, HistoryConfig(maxRangeSeconds = 86_400, maxPoints = 25), Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC))

    @Test fun `history parses bucket and delegates bounded SQL query`() {
        val result = service.history(userId, plantId, "2026-07-26T10:00:00Z", "2026-07-26T12:00:00Z", "5m")
        assertEquals(HistoryInterval.FIVE_MINUTES, result.interval)
        assertEquals(HistoryInterval.FIVE_MINUTES, captured?.interval)
    }

    @Test fun `history rejects invalid and excessive ranges`() {
        assertEquals("INVALID_DATE_RANGE", assertFailsWith<UserApiException> { service.history(userId, plantId, "2026-07-26T12:00:00Z", "2026-07-26T11:00:00Z", "raw") }.code)
        assertEquals("DATE_RANGE_TOO_LARGE", assertFailsWith<UserApiException> { service.history(userId, plantId, "2026-07-24T12:00:00Z", "2026-07-26T12:00:00Z", "raw") }.code)
        assertEquals("INVALID_INTERVAL", assertFailsWith<UserApiException> { service.history(userId, plantId, null, null, "2h") }.code)
    }

    @Test fun `subscription ownership does not disclose another plant`() {
        assertEquals(404, assertFailsWith<UserApiException> { service.requireOwnership(UUID.randomUUID(), plantId) }.status)
    }
}
