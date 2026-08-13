package com.alad1nks.jaiqal.feature.plants.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.feature.plants.domain.HistoryRange
import com.alad1nks.jaiqal.feature.plants.domain.PlantDetails
import com.alad1nks.jaiqal.feature.plants.domain.PlantOverview
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlantScreenTest {
    @Test
    fun emptyAndPopulatedStatesUseStableSemantics() = runComposeUiTest {
        var createClicks = 0
        setContent {
            JaiqalTheme { PlantsEmptyState(onCreatePlant = { createClicks++ }, onClaimDevice = {}) }
        }
        onNodeWithTag(PlantUiTags.EMPTY).assertIsDisplayed()

        var cardClicks = 0
        setContent {
            JaiqalTheme { PlantCard(overview("Aloe"), onClick = { cardClicks++ }) }
        }
        onNodeWithTag(PlantUiTags.CARD).assertIsDisplayed().performClick()
        assertEquals(1, cardClicks)
    }

    @Test
    fun detailsExplicitlyExposeMissingReadings() = runComposeUiTest {
        setContent {
            JaiqalTheme {
                PlantDetailsContent(
                    details = PlantDetails(overview("Aloe"), history = null),
                    cached = true,
                    selectedRange = HistoryRange.LAST_24_HOURS,
                    historyLoading = false,
                    historyError = null,
                    onSelectHistoryRange = {},
                    onRetryHistory = {},
                    onClaimDevice = {},
                    onDeviceDetails = {},
                    onCalibrate = {},
                )
            }
        }

        onNodeWithTag(PlantUiTags.DETAILS).assertIsDisplayed()
        onNodeWithTag(PlantUiTags.MISSING_READINGS).assertIsDisplayed()
        onNodeWithTag(PlantUiTags.OFFLINE_CACHE).assertIsDisplayed()
    }

    private fun overview(name: String) = PlantOverview(
        plant = PlantResponse("plant-a", name, null, null, "2026-08-13T00:00:00Z"),
        device = null,
        latest = null,
        activeAlerts = emptyList(),
    )
}
