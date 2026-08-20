package com.alad1nks.jaiqal.alerts

import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.api.contract.PutAlertRuleRequest
import com.alad1nks.jaiqal.api.contract.PutAlertRulesRequest
import com.alad1nks.jaiqal.users.UserApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlertValidationTest {
    @Test
    fun `accepts at most one rule for every public alert type`() {
        validateAlertRules(
            PutAlertRulesRequest(
                AlertType.entries.map { type ->
                    PutAlertRuleRequest(
                        type = type,
                        threshold = if (type == AlertType.DEVICE_OFFLINE) 60.0 else 25.0,
                    )
                },
            ),
        )
    }

    @Test
    fun `rejects more rules than public alert types before per-rule validation`() {
        val rule = PutAlertRuleRequest(AlertType.LOW_SOIL_MOISTURE, threshold = 25.0)

        val error = assertFailsWith<UserApiException> {
            validateAlertRules(PutAlertRulesRequest(List(AlertType.entries.size + 1) { rule }))
        }

        assertEquals(400, error.status)
        assertEquals("INVALID_ALERT_RULE_COUNT", error.code)
    }

    @Test
    fun `rejects duplicate alert types`() {
        val rule = PutAlertRuleRequest(AlertType.HIGH_TEMPERATURE, threshold = 30.0)

        val error = assertFailsWith<UserApiException> {
            validateAlertRules(PutAlertRulesRequest(listOf(rule, rule)))
        }

        assertEquals(400, error.status)
        assertEquals("DUPLICATE_ALERT_TYPE", error.code)
    }
}
