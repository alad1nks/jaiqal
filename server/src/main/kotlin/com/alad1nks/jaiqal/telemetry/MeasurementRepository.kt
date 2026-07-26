package com.alad1nks.jaiqal.telemetry

import java.time.OffsetDateTime
import java.util.UUID

data class NewMeasurement(
    val deviceId: UUID, val sequence: Long, val measuredAt: OffsetDateTime,
    val receivedAt: OffsetDateTime, val soilMoistureRaw: Int? = null,
    val soilMoisturePercent: Double? = null, val airTemperatureCelsius: Double? = null,
    val airHumidityPercent: Double? = null, val lightRaw: Int? = null, val extra: String = "{}",
)

data class MeasurementRecord(val id: Long, val measurement: NewMeasurement)
data class LatestDeviceState(val deviceId: UUID, val measurementId: Long, val updatedAt: OffsetDateTime)

interface MeasurementRepository {
    /** Returns null when the device/sequence pair already exists. */
    fun insert(measurement: NewMeasurement): MeasurementRecord?
    fun findByDeviceAndSequence(deviceId: UUID, sequence: Long): MeasurementRecord?
    fun upsertLatest(state: LatestDeviceState)
    fun findLatest(deviceId: UUID): LatestDeviceState?
}
