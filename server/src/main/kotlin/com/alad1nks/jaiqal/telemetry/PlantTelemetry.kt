package com.alad1nks.jaiqal.telemetry

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.config.HistoryConfig
import com.alad1nks.jaiqal.users.UserApiException
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class HistoryRequest(val from: OffsetDateTime, val to: OffsetDateTime, val interval: HistoryInterval)

interface PlantTelemetryRepository {
    fun latest(userId: UUID, plantId: UUID): PlantLatestResponse?
    fun history(userId: UUID, plantId: UUID, request: HistoryRequest, limit: Int): List<PlantHistoryPoint>?
    fun ownsPlant(userId: UUID, plantId: UUID): Boolean
}

class PlantTelemetryService(
    private val repository: PlantTelemetryRepository,
    private val config: HistoryConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun latest(userId: UUID, plantId: UUID): PlantLatestResponse =
        repository.latest(userId, plantId)?.let { value ->
            value.copy(online = OffsetDateTime.parse(value.receivedAt).isAfter(OffsetDateTime.now(clock).minusSeconds(config.onlineWindowSeconds)))
        } ?: notFound()

    fun history(userId: UUID, plantId: UUID, from: String?, to: String?, interval: String?): PlantHistoryResponse {
        val end = parse(to) ?: OffsetDateTime.now(clock)
        val start = parse(from) ?: end.minusSeconds(config.defaultRangeSeconds)
        if (!start.isBefore(end)) bad("INVALID_DATE_RANGE", "from must be earlier than to")
        if (Duration.between(start, end).seconds > config.maxRangeSeconds) bad("DATE_RANGE_TOO_LARGE", "Requested date range is too large")
        val selected = when (interval?.lowercase() ?: "raw") {
            "raw" -> HistoryInterval.RAW
            "5m" -> HistoryInterval.FIVE_MINUTES
            "1h" -> HistoryInterval.ONE_HOUR
            "1d" -> HistoryInterval.ONE_DAY
            else -> bad("INVALID_INTERVAL", "interval must be raw, 5m, 1h, or 1d")
        }
        val points = repository.history(userId, plantId, HistoryRequest(start, end, selected), config.maxPoints) ?: notFound()
        return PlantHistoryResponse(plantId.toString(), selected, points)
    }

    fun requireOwnership(userId: UUID, plantId: UUID) { if (!repository.ownsPlant(userId, plantId)) notFound() }
    private fun parse(value: String?): OffsetDateTime? = value?.let {
        runCatching { OffsetDateTime.parse(it) }.getOrElse { bad("INVALID_DATE", "Dates must use ISO-8601 with an offset") }
    }
    private fun bad(code: String, message: String): Nothing = throw UserApiException(400, code, message)
    private fun notFound(): Nothing = throw UserApiException(404, "NOT_FOUND", "Resource was not found")
}
