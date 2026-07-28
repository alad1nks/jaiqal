package com.alad1nks.jaiqal.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.core.network.BackendConfig
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.backend_environment
import jaiqal.app.shared.generated.resources.settings
import jaiqal.app.shared.generated.resources.settings_message
import jaiqal.app.shared.generated.resources.theme_dark
import jaiqal.app.shared.generated.resources.theme_light
import jaiqal.app.shared.generated.resources.theme_system
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsPlaceholderScreen(
    backendConfig: BackendConfig,
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(JaiqalTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
    ) {
        Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(Res.string.settings_message), style = MaterialTheme.typography.bodyLarge)
        JaiqalCard(Modifier.fillMaxWidth()) {
            Text(
                stringResource(Res.string.backend_environment, backendConfig.environment.name.lowercase()),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        val labels = listOf(
            ThemeMode.SYSTEM to stringResource(Res.string.theme_system),
            ThemeMode.LIGHT to stringResource(Res.string.theme_light),
            ThemeMode.DARK to stringResource(Res.string.theme_dark),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}
