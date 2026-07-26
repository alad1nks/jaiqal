package com.alad1nks.jaiqal.api.contract

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class DeviceMeasurementRequest(
    val sequence: Long,
    val firmwareVersion: String? = null,
    val measuredAt: Instant? = null,
    val soilMoistureRaw: Int? = null,
    val airTemperatureCelsius: Double? = null,
    val airHumidityPercent: Double? = null,
    val lightRaw: Int? = null,
)

@Serializable
data class DeviceMeasurementResponse(
    val accepted: Boolean,
    val duplicate: Boolean,
    val serverTime: Instant,
    val nextUploadSeconds: Int,
)

@Serializable
data class DeviceMeasurementBatchRequest(val measurements: List<DeviceMeasurementRequest>)

@Serializable
data class DeviceMeasurementBatchResponse(val results: List<DeviceMeasurementResponse>)
