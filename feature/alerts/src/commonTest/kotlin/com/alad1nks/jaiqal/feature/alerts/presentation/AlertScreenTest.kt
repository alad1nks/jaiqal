package com.alad1nks.jaiqal.feature.alerts.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.feature.alerts.domain.AlertOverview
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AlertScreenTest {
    @Test
    fun activeAlertCardIsReachableWithoutLocalizedText() = runComposeUiTest {
        setContent {
            JaiqalTheme {
                AlertCard(
                    alert = AlertOverview(
                        plantId = "plant-a",
                        plantName = "Aloe",
                        event = AlertEventResponse(
                            id = "alert-a",
                            type = AlertType.LOW_SOIL_MOISTURE,
                            status = AlertStatus.ACTIVE,
                            triggeredAt = "2026-08-13T00:00:00Z",
                            lastObservedAt = "2026-08-13T00:00:00Z",
                        ),
                    ),
                    acknowledging = false,
                    onAcknowledge = {},
                )
            }
        }

        onNodeWithTag(AlertUiTags.CARD).assertIsDisplayed()
    }
}
