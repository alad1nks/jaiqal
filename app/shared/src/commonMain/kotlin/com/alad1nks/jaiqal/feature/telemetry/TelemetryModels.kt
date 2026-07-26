package com.alad1nks.jaiqal.feature.telemetry

import com.alad1nks.jaiqal.api.contract.PlantLatestResponse

data class LatestReading(
    val measuredAt: String,
    val soilPercent: Double?,
    val soilRaw: Int?,
    val temperatureCelsius: Double?,
    val humidityPercent: Double?,
    val lightRaw: Int?,
    val online: Boolean,
    val calibrated: Boolean,
)

fun PlantLatestResponse.toDomain() = LatestReading(
    measuredAt, soilMoisturePercent, soilMoistureRaw, airTemperatureCelsius,
    airHumidityPercent, lightRaw, online, calibrated,
)

enum class HistoryRange(val interval: String) {
    DAY("raw"), WEEK("5m"), MONTH("1h")
}
