package com.alad1nks.jaiqal.telemetry

import com.alad1nks.jaiqal.api.contract.DeviceMeasurementRequest
import com.alad1nks.jaiqal.api.contract.DeviceMeasurementResponse
import com.alad1nks.jaiqal.config.TelemetryConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import kotlin.time.Instant
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PreparedMeasurement(val measurement: NewMeasurement, val firmwareVersion: String?)
data class IngestionResult(val measurement: MeasurementRecord?, val duplicate: Boolean)

fun interface TelemetryStore {
    fun ingest(device: DeviceRecord, measurements: List<PreparedMeasurement>): List<IngestionResult>
}

data class MeasurementReceived(val plantId: UUID?, val measurement: MeasurementRecord)
fun interface MeasurementEventPublisher {
    fun publish(event: MeasurementReceived)
    companion object { fun noop() = MeasurementEventPublisher { } }
}

class TelemetryValidationException(val errorCode: String, override val message: String) : IllegalArgumentException(message)
class TelemetryQuotaExceededException(val retryAfterSeconds: Long) : RuntimeException("Per-device ingestion quota exceeded")
class DeviceQuarantinedException(val retryAfterSeconds: Long) : RuntimeException("Device ingestion is temporarily quarantined")

data class IngestionQuotaDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
    val quarantined: Boolean = false,
)

fun interface DeviceIngestionQuota {
    fun tryConsume(deviceId: UUID, measurementCount: Int): IngestionQuotaDecision

    companion object {
        fun unlimited() = DeviceIngestionQuota { _, _ -> IngestionQuotaDecision(allowed = true) }
    }
}

class TelemetryIngestionService(
    private val store: TelemetryStore,
    private val config: TelemetryConfig,
    private val publisher: MeasurementEventPublisher = MeasurementEventPublisher.noop(),
    private val clock: Clock = Clock.systemUTC(),
    private val quota: DeviceIngestionQuota = DeviceIngestionQuota.unlimited(),
) {
    fun ingest(device: DeviceRecord, requests: List<DeviceMeasurementRequest>): List<DeviceMeasurementResponse> {
        if (requests.size !in 1..100) invalid("INVALID_BATCH_SIZE", "Batch size must be between 1 and 100")
        val now = OffsetDateTime.now(clock)
        val prepared = requests.map { prepare(device, it, now) }
        val quotaDecision = quota.tryConsume(device.id, prepared.size)
        if (!quotaDecision.allowed) {
            val retryAfter = quotaDecision.retryAfterSeconds.coerceAtLeast(1)
            if (quotaDecision.quarantined) throw DeviceQuarantinedException(retryAfter)
            throw TelemetryQuotaExceededException(retryAfter)
        }
        val results = store.ingest(device, prepared)
        results.mapNotNull(IngestionResult::measurement).forEach { publisher.publish(MeasurementReceived(device.plantId, it)) }
        return results.map {
            DeviceMeasurementResponse(true, it.duplicate, now.toApiInstant(), config.nextUploadSeconds)
        }
    }

    private fun prepare(device: DeviceRecord, request: DeviceMeasurementRequest, now: OffsetDateTime): PreparedMeasurement {
        if (request.sequence < 0) invalid("INVALID_SEQUENCE", "Sequence must be non-negative")
        if (request.soilMoistureRaw == null && request.airTemperatureCelsius == null &&
            request.airHumidityPercent == null && request.lightRaw == null
        ) invalid("SENSOR_VALUE_REQUIRED", "At least one sensor value is required")
        request.airHumidityPercent?.let { if (!it.isFinite() || it !in 0.0..100.0) invalid("INVALID_HUMIDITY", "Humidity must be between 0 and 100") }
        request.airTemperatureCelsius?.let { if (!it.isFinite() || it !in config.minTemperatureCelsius..config.maxTemperatureCelsius) invalid("INVALID_TEMPERATURE", "Temperature is outside the accepted range") }
        request.soilMoistureRaw?.validateAdc()
        request.lightRaw?.validateAdc()
        val firmwareVersion = request.firmwareVersion?.trim()?.takeIf(String::isNotEmpty)
        if (
            firmwareVersion != null &&
            (firmwareVersion.length > MAX_FIRMWARE_VERSION_LENGTH || firmwareVersion.any(Char::isISOControl))
        ) {
            invalid(
                "INVALID_FIRMWARE_VERSION",
                "Firmware version must contain at most 100 characters without control characters",
            )
        }

        val suppliedTime = request.measuredAt?.toOffsetDateTime()
        val validTime = suppliedTime?.takeIf {
            !it.isBefore(now.minusSeconds(config.pastWindowSeconds)) && !it.isAfter(now.plusSeconds(config.futureWindowSeconds))
        }
        val fallback = request.measuredAt == null || validTime == null
        return PreparedMeasurement(
            NewMeasurement(
                deviceId = device.id, sequence = request.sequence, measuredAt = validTime ?: now,
                receivedAt = now, soilMoistureRaw = request.soilMoistureRaw,
                soilMoisturePercent = calculateSoilMoisturePercent(request.soilMoistureRaw, device.soilDryRaw, device.soilWetRaw),
                airTemperatureCelsius = request.airTemperatureCelsius,
                airHumidityPercent = request.airHumidityPercent, lightRaw = request.lightRaw,
                extra = if (fallback) "{\"measuredAtFallback\":true,\"reason\":\"${if (request.measuredAt == null) "missing" else "outside_window"}\"}" else "{}",
            ),
            firmwareVersion,
        )
    }

    private fun Int.validateAdc() { if (this !in config.minAdc..config.maxAdc) invalid("INVALID_ADC", "ADC value is outside the accepted range") }
    private fun invalid(code: String, message: String): Nothing = throw TelemetryValidationException(code, message)

    private companion object {
        const val MAX_FIRMWARE_VERSION_LENGTH = 100
    }
}

fun calculateSoilMoisturePercent(raw: Int?, dryRaw: Int?, wetRaw: Int?): Double? {
    if (raw == null || dryRaw == null || wetRaw == null || dryRaw == wetRaw) return null
    return ((raw - dryRaw).toDouble() / (wetRaw - dryRaw).toDouble() * 100.0).coerceIn(0.0, 100.0)
}

private fun Instant.toOffsetDateTime() = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()), ZoneOffset.UTC)
private fun OffsetDateTime.toApiInstant() = Instant.fromEpochSeconds(toEpochSecond(), nano)
