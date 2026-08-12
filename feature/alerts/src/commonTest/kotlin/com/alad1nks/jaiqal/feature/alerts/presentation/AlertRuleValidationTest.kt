package com.alad1nks.jaiqal.feature.alerts.presentation

import com.alad1nks.jaiqal.api.contract.AlertType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlertRuleValidationTest {
    @Test
    fun validationMatchesBackendLimits() {
        val valid = AlertRuleDraft(
            type = AlertType.LOW_SOIL_MOISTURE,
            configured = true,
            threshold = "25.5",
            requiredDurationSeconds = "2592000",
            recoveryDurationSeconds = "0",
        ).validatedRequest()

        assertEquals(25.5, valid.threshold)
        assertEquals(2_592_000, valid.requiredDurationSeconds)
    }

    @Test
    fun invalidThresholdAndDurationAreRejectedBeforeNetwork() {
        val thresholdFailure = assertFailsWith<InvalidRule> {
            AlertRuleDraft(
                type = AlertType.LOW_SOIL_MOISTURE,
                configured = true,
                threshold = "101",
            ).validatedRequest()
        }
        assertEquals(AlertUiError.INVALID_THRESHOLD, thresholdFailure.error)

        val durationFailure = assertFailsWith<InvalidRule> {
            AlertRuleDraft(
                type = AlertType.DEVICE_OFFLINE,
                configured = true,
                threshold = "60",
                requiredDurationSeconds = "2592001",
            ).validatedRequest()
        }
        assertEquals(AlertUiError.INVALID_DURATION, durationFailure.error)
    }
}
