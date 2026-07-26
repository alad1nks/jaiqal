package com.alad1nks.jaiqal.alerts

import java.time.OffsetDateTime
import java.util.UUID

data class AlertRuleRecord(
    val id: UUID, val plantId: UUID, val type: String, val threshold: Double?,
    val requiredDurationSeconds: Long, val recoveryDurationSeconds: Long, val enabled: Boolean,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

interface AlertRuleRepository {
    fun create(rule: AlertRuleRecord): AlertRuleRecord
    fun findByPlantId(plantId: UUID): List<AlertRuleRecord>
}
