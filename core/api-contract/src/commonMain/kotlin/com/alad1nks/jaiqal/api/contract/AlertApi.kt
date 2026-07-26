package com.alad1nks.jaiqal.api.contract

import kotlinx.serialization.Serializable

@Serializable
enum class AlertType { LOW_SOIL_MOISTURE, HIGH_TEMPERATURE, LOW_TEMPERATURE, DEVICE_OFFLINE }

@Serializable
enum class AlertStatus { ACTIVE, RECOVERED }

@Serializable
data class AlertRuleResponse(
    val id: String,
    val type: AlertType,
    val threshold: Double? = null,
    val requiredDurationSeconds: Long,
    val recoveryDurationSeconds: Long,
    val enabled: Boolean,
)

@Serializable
data class PutAlertRuleRequest(
    val type: AlertType,
    val threshold: Double? = null,
    val requiredDurationSeconds: Long = 0,
    val recoveryDurationSeconds: Long = 0,
    val enabled: Boolean = true,
)

@Serializable
data class PutAlertRulesRequest(val rules: List<PutAlertRuleRequest>)

@Serializable
data class AlertEventResponse(
    val id: String,
    val type: AlertType,
    val status: AlertStatus,
    val triggeredAt: String,
    val recoveredAt: String? = null,
    val acknowledgedAt: String? = null,
    val lastObservedAt: String,
)
