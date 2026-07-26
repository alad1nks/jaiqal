package com.alad1nks.jaiqal.alerts

import java.time.Duration
import java.time.OffsetDateTime

data class AlertEvaluationState(
    val conditionSince: OffsetDateTime? = null,
    val recoverySince: OffsetDateTime? = null,
    val active: Boolean = false,
)

enum class AlertTransition { NONE, OPEN, CLOSE }
data class AlertEvaluation(val state: AlertEvaluationState, val transition: AlertTransition)

/** Pure duration/de-duplication state machine. The caller persists the returned state atomically. */
object AlertEngine {
    fun evaluate(
        previous: AlertEvaluationState,
        conditionMet: Boolean,
        observedAt: OffsetDateTime,
        requiredDurationSeconds: Long,
        recoveryDurationSeconds: Long,
    ): AlertEvaluation {
        require(requiredDurationSeconds >= 0 && recoveryDurationSeconds >= 0)
        if (!previous.active) {
            if (!conditionMet) return AlertEvaluation(AlertEvaluationState(), AlertTransition.NONE)
            val since = previous.conditionSince ?: observedAt
            val ready = elapsed(since, observedAt) >= requiredDurationSeconds
            return if (ready) AlertEvaluation(AlertEvaluationState(active = true), AlertTransition.OPEN)
            else AlertEvaluation(AlertEvaluationState(conditionSince = since), AlertTransition.NONE)
        }
        if (conditionMet) return AlertEvaluation(AlertEvaluationState(active = true), AlertTransition.NONE)
        val since = previous.recoverySince ?: observedAt
        val recovered = elapsed(since, observedAt) >= recoveryDurationSeconds
        return if (recovered) AlertEvaluation(AlertEvaluationState(), AlertTransition.CLOSE)
        else AlertEvaluation(AlertEvaluationState(recoverySince = since, active = true), AlertTransition.NONE)
    }

    private fun elapsed(from: OffsetDateTime, to: OffsetDateTime): Long =
        Duration.between(from, to).seconds.coerceAtLeast(0)
}
