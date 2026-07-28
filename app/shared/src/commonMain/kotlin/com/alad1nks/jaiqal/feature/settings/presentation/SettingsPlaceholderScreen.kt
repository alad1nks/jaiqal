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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.backend_environment
import jaiqal.app.shared.generated.resources.settings
import jaiqal.app.shared.generated.resources.settings_message
import jaiqal.app.shared.generated.resources.sign_out
import jaiqal.app.shared.generated.resources.theme_dark
import jaiqal.app.shared.generated.resources.theme_light
import jaiqal.app.shared.generated.resources.theme_system
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsPlaceholderScreen(
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val userSession by viewModel.userSession.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(JaiqalTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
    ) {
        Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(Res.string.settings_message), style = MaterialTheme.typography.bodyLarge)
        JaiqalCard(Modifier.fillMaxWidth()) {
            Text(
                stringResource(Res.string.backend_environment, viewModel.environmentName),
                style = MaterialTheme.typography.labelLarge,
            )
            userSession?.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
        JaiqalButton(
            text = stringResource(Res.string.sign_out),
            onClick = viewModel::signOut,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
