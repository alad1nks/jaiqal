package com.alad1nks.jaiqal.feature.settings.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.core.preferences.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    @Test
    fun themeAndLanguageChoicesExposeSelectionSemantics() = runComposeUiTest {
        var clicks = 0
        setContent {
            JaiqalTheme {
                Column {
                    PreferenceChoice(
                        "Русский",
                        selected = true,
                        onClick = { clicks++ },
                        SettingsUiTags.language(AppLanguage.RUSSIAN),
                    )
                    PreferenceChoice(
                        "Dark",
                        selected = false,
                        onClick = {},
                        SettingsUiTags.theme(ThemeMode.DARK),
                    )
                }
            }
        }

        onNodeWithTag(SettingsUiTags.language(AppLanguage.RUSSIAN))
            .assertIsDisplayed().assertIsSelected().performClick()
        onNodeWithTag(SettingsUiTags.theme(ThemeMode.DARK)).assertIsDisplayed()
        assertEquals(1, clicks)
    }
}
