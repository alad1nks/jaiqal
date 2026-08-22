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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.alad1nks.jaiqal.core.auth.AccountAuthMethod
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

object SettingsUiTags {
    fun language(language: AppLanguage) = "settings.language.${language.name.lowercase()}"
    fun theme(theme: ThemeMode) = "settings.theme.${theme.name.lowercase()}"
    const val DELETE_ACCOUNT = "settings.delete_account"
    const val DELETE_DIALOG = "settings.delete_account.dialog"
    const val DELETE_PASSWORD = "settings.delete_account.password"
    const val DELETE_CONFIRM = "settings.delete_account.confirm"
}

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
    var deletionPassword by remember(state.showDeleteConfirmation) { mutableStateOf("") }
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
                    testTag = SettingsUiTags.language(option),
                )
            }
        }
        PreferenceSection(stringResource(Res.string.theme)) {
            ThemeMode.entries.forEach { option ->
                PreferenceChoice(
                    label = stringResource(option.labelResource()),
                    selected = themeMode == option,
                    onClick = { onThemeSelected(option) },
                    testTag = SettingsUiTags.theme(option),
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
        OutlinedButton(
            onClick = viewModel::requestAccountDeletion,
            modifier = Modifier.fillMaxWidth().testTag(SettingsUiTags.DELETE_ACCOUNT),
            enabled = !state.isDeletingAccount,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(Res.string.delete_account))
        }
    }
    if (state.showDeleteConfirmation) {
        AlertDialog(
            modifier = Modifier.testTag(SettingsUiTags.DELETE_DIALOG),
            onDismissRequest = viewModel::cancelAccountDeletion,
            title = { Text(stringResource(Res.string.delete_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.small)) {
                    Text(stringResource(Res.string.delete_account_warning))
                    Text(stringResource(Res.string.delete_account_reauthenticate))
                    if (state.authMethod == AccountAuthMethod.PASSWORD) {
                        OutlinedTextField(
                            value = deletionPassword,
                            onValueChange = { deletionPassword = it },
                            modifier = Modifier.fillMaxWidth().testTag(SettingsUiTags.DELETE_PASSWORD),
                            label = { Text(stringResource(Res.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !state.isDeletingAccount,
                        )
                    }
                    if (state.isDeletingAccount) CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmAccountDeletion(deletionPassword.takeIf(String::isNotBlank)) },
                    modifier = Modifier.testTag(SettingsUiTags.DELETE_CONFIRM),
                    enabled = !state.isDeletingAccount &&
                        (state.authMethod != AccountAuthMethod.PASSWORD || deletionPassword.isNotBlank()),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.delete_account_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelAccountDeletion,
                    enabled = !state.isDeletingAccount,
                ) { Text(stringResource(Res.string.cancel)) }
            },
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
internal fun PreferenceChoice(label: String, selected: Boolean, onClick: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(testTag).semantics { this.selected = selected }
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
        SettingsUiError.INVALID_CREDENTIALS -> Res.string.settings_invalid_credentials
        SettingsUiError.UNKNOWN -> Res.string.settings_unknown_error
    },
)
