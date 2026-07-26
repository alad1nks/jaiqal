package com.alad1nks.jaiqal.feature.devices

sealed interface CalibrationStep {
    data object Introduction : CalibrationStep
    data object DrySample : CalibrationStep
    data object WetSample : CalibrationStep
    data class Review(val dry: Int, val wet: Int) : CalibrationStep
    data object Saving : CalibrationStep
    data object Complete : CalibrationStep
}

fun calibrationError(dry: Int, wet: Int): String? = when {
    dry == wet -> "CALIBRATION_VALUES_EQUAL"
    dry !in 0..4095 || wet !in 0..4095 -> "CALIBRATION_VALUE_OUT_OF_RANGE"
    else -> null // Both ADC directions are valid and are interpreted by the server.
}
