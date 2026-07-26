package com.alad1nks.jaiqal.api.contract

import kotlinx.serialization.Serializable

@Serializable
data class PlantLatestResponse(
    val plantId: String,
    val deviceId: String,
    val measuredAt: String,
    val receivedAt: String,
    val soilMoisturePercent: Double? = null,
    val soilMoistureRaw: Int? = null,
    val airTemperatureCelsius: Double? = null,
    val airHumidityPercent: Double? = null,
    val lightRaw: Int? = null,
    val online: Boolean,
    val calibrated: Boolean,
)

@Serializable
enum class HistoryInterval { RAW, FIVE_MINUTES, ONE_HOUR, ONE_DAY }

@Serializable
data class PlantHistoryPoint(
    val measuredAt: String,
    val soilMoisturePercent: Double? = null,
    val soilMoistureRaw: Double? = null,
    val airTemperatureCelsius: Double? = null,
    val airHumidityPercent: Double? = null,
    val lightRaw: Double? = null,
)

@Serializable
data class PlantHistoryResponse(
    val plantId: String,
    val interval: HistoryInterval,
    val points: List<PlantHistoryPoint>,
)

@Serializable
data class PlantTelemetryUpdate(val plantId: String, val deviceId: String, val measurementId: Long)
