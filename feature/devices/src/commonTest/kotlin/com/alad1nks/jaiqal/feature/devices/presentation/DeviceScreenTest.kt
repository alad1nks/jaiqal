package com.alad1nks.jaiqal.feature.devices.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.feature.devices.domain.CalibrationSample
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DeviceScreenTest {
    @Test
    fun claimCodeErrorHasLocaleIndependentSemantics() = runComposeUiTest {
        setContent { JaiqalTheme { DeviceErrorBadge(DeviceUiError.CODE_UNAVAILABLE) } }
        onNodeWithTag(DeviceUiTags.CLAIM_ERROR).assertIsDisplayed()
    }

    @Test
    fun calibrationRendersEveryStepWithStableSemantics() = runComposeUiTest {
        CalibrationStep.entries.forEach { step ->
            setContent {
                JaiqalTheme {
                    CalibrationStepContent(
                        CalibrationUiState(
                            step = step,
                            drySample = if (step.ordinal >= CalibrationStep.WET_SAMPLE.ordinal) sample(800) else null,
                            wetSample = if (step.ordinal >= CalibrationStep.REVIEW.ordinal) sample(300) else null,
                        ),
                    )
                }
            }
            onNodeWithTag(DeviceUiTags.calibrationStep(step)).assertIsDisplayed()
        }
    }

    private fun sample(raw: Int) = CalibrationSample(raw, "2026-08-13T00:00:00Z")
}
