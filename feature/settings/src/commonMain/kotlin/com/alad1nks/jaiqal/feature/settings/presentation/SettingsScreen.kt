package com.alad1nks.jaiqal.feature.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.component.StatusBadge
import com.alad1nks.jaiqal.core.designsystem.component.StatusKind
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.core.preferences.AppLanguage
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    language: AppLanguage,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onOpenPrivacyPolicy: (String) -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(JaiqalTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
    ) {
        Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineMedium)
        PreferenceSection(stringResource(Res.string.language)) {
            AppLanguage.entries.forEach { option ->
                PreferenceChoice(
                    label = stringResource(option.labelResource()),
                    selected = language == option,
                    onClick = { onLanguageSelected(option) },
                )
            }
        }
        PreferenceSection(stringResource(Res.string.theme)) {
            ThemeMode.entries.forEach { option ->
                PreferenceChoice(
                    label = stringResource(option.labelResource()),
                    selected = themeMode == option,
                    onClick = { onThemeSelected(option) },
                )
            }
        }
        PreferenceSection(stringResource(Res.string.account)) {
            Text(state.user?.email ?: stringResource(Res.string.account_email_unavailable))
            StatusBadge(
                text = stringResource(
                    if (state.emailVerified) Res.string.email_verified else Res.string.email_not_verified,
                ),
                kind = if (state.emailVerified) StatusKind.SUCCESS else StatusKind.WARNING,
            )
            OutlinedButton(
                onClick = viewModel::resendVerificationEmail,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSendingVerification,
            ) { Text(stringResource(Res.string.resend_verification)) }
            if (state.isSendingVerification) CircularProgressIndicator()
            if (state.verificationSent) {
                StatusBadge(stringResource(Res.string.verification_email_sent), StatusKind.SUCCESS)
            }
        }
        PreferenceSection(stringResource(Res.string.about_app)) {
            Text(stringResource(Res.string.app_version, state.appVersion))
            state.privacyPolicyUrl?.let { url ->
                OutlinedButton(
                    onClick = { onOpenPrivacyPolicy(url) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.privacy_policy)) }
            } ?: Text(stringResource(Res.string.privacy_policy_placeholder))
        }
        state.diagnostics?.let { diagnostics ->
            PreferenceSection(stringResource(Res.string.diagnostics)) {
                Text(stringResource(Res.string.diagnostics_debug_only), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.diagnostics_platform, diagnostics.platform))
                Text(stringResource(Res.string.diagnostics_environment, diagnostics.environment))
                Text(stringResource(Res.string.diagnostics_backend, diagnostics.backendBaseUrl))
            }
        }
        state.error?.let { StatusBadge(settingsErrorMessage(it), StatusKind.ERROR) }
        JaiqalButton(
            text = stringResource(Res.string.sign_out),
            onClick = viewModel::signOut,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSigningOut,
        )
    }
}

@Composable
private fun PreferenceSection(title: String, content: @Composable () -> Unit) {
    JaiqalCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun PreferenceChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = JaiqalTheme.spacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

private fun AppLanguage.labelResource(): StringResource = when (this) {
    AppLanguage.SYSTEM -> Res.string.language_system
    AppLanguage.KAZAKH -> Res.string.language_kazakh
    AppLanguage.RUSSIAN -> Res.string.language_russian
    AppLanguage.ENGLISH -> Res.string.language_english
}

private fun ThemeMode.labelResource(): StringResource = when (this) {
    ThemeMode.SYSTEM -> Res.string.theme_system
    ThemeMode.LIGHT -> Res.string.theme_light
    ThemeMode.DARK -> Res.string.theme_dark
}

@Composable
private fun settingsErrorMessage(error: SettingsUiError): String = stringResource(
    when (error) {
        SettingsUiError.NETWORK -> Res.string.settings_network_error
        SettingsUiError.TOO_MANY_REQUESTS -> Res.string.settings_too_many_requests
        SettingsUiError.NO_USER -> Res.string.settings_no_user
        SettingsUiError.UNKNOWN -> Res.string.settings_unknown_error
    },
)
