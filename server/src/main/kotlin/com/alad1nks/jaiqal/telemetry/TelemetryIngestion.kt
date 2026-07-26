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

data class MeasurementReceived(val measurement: MeasurementRecord)
fun interface MeasurementEventPublisher {
    fun publish(event: MeasurementReceived)
    companion object { fun noop() = MeasurementEventPublisher { } }
}

class TelemetryValidationException(val errorCode: String, override val message: String) : IllegalArgumentException(message)

class TelemetryIngestionService(
    private val store: TelemetryStore,
    private val config: TelemetryConfig,
    private val publisher: MeasurementEventPublisher = MeasurementEventPublisher.noop(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun ingest(device: DeviceRecord, requests: List<DeviceMeasurementRequest>): List<DeviceMeasurementResponse> {
        if (requests.size !in 1..100) invalid("INVALID_BATCH_SIZE", "Batch size must be between 1 and 100")
        val now = OffsetDateTime.now(clock)
        val prepared = requests.map { prepare(device, it, now) }
        val results = store.ingest(device, prepared)
        results.mapNotNull(IngestionResult::measurement).forEach { publisher.publish(MeasurementReceived(it)) }
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
            request.firmwareVersion,
        )
    }

    private fun Int.validateAdc() { if (this !in config.minAdc..config.maxAdc) invalid("INVALID_ADC", "ADC value is outside the accepted range") }
    private fun invalid(code: String, message: String): Nothing = throw TelemetryValidationException(code, message)
}

fun calculateSoilMoisturePercent(raw: Int?, dryRaw: Int?, wetRaw: Int?): Double? {
    if (raw == null || dryRaw == null || wetRaw == null || dryRaw == wetRaw) return null
    return ((raw - dryRaw).toDouble() / (wetRaw - dryRaw).toDouble() * 100.0).coerceIn(0.0, 100.0)
}

private fun Instant.toOffsetDateTime() = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()), ZoneOffset.UTC)
private fun OffsetDateTime.toApiInstant() = Instant.fromEpochSeconds(toEpochSecond(), nano)
