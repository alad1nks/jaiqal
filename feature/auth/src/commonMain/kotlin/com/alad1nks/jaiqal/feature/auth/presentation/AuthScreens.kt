package com.alad1nks.jaiqal.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalTextField
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

object AuthUiTags {
    const val LOGIN = "auth.login"
    const val REGISTER = "auth.register"
    const val VERIFY_EMAIL = "auth.verify-email"
    const val EMAIL = "auth.email"
    const val PASSWORD = "auth.password"
    const val SUBMIT = "auth.submit"
    const val ERROR = "auth.error"
}

@Composable
fun LoginScreen(
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(state, viewModel::setEmail, viewModel::setPassword, viewModel::signIn, onRegister, onForgotPassword)
}

@Composable
internal fun LoginContent(
    state: AuthFormUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    AuthLayout(Res.string.login_title, Res.string.login_message) {
        AuthFields(state, onEmailChange, onPasswordChange, showPassword = true)
        JaiqalButton(
            text = stringResource(Res.string.sign_in),
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().testTag(AuthUiTags.SUBMIT),
            enabled = !state.isLoading,
        )
        AuthFeedback(state)
        TextButton(onClick = onForgotPassword, enabled = !state.isLoading) {
            Text(stringResource(Res.string.forgot_password))
        }
        TextButton(onClick = onRegister, enabled = !state.isLoading) {
            Text(stringResource(Res.string.register))
        }
    }
}

@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RegisterContent(state, viewModel::setEmail, viewModel::setPassword, viewModel::signUp, onBack)
}

@Composable
internal fun RegisterContent(
    state: AuthFormUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    AuthLayout(Res.string.register, Res.string.register_message, AuthUiTags.REGISTER) {
        AuthFields(state, onEmailChange, onPasswordChange, showPassword = true)
        Text(stringResource(Res.string.password_requirements), style = MaterialTheme.typography.bodySmall)
        JaiqalButton(
            text = stringResource(Res.string.register),
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().testTag(AuthUiTags.SUBMIT),
            enabled = !state.isLoading,
        )
        AuthFeedback(state)
        TextButton(onClick = onBack, enabled = !state.isLoading) {
            Text(stringResource(Res.string.back_to_login))
        }
    }
}

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthLayout(Res.string.forgot_password, Res.string.forgot_password_message) {
        AuthFields(state, viewModel::setEmail, viewModel::setPassword, showPassword = false)
        JaiqalButton(
            text = stringResource(Res.string.send_reset_email),
            onClick = viewModel::sendPasswordReset,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
        )
        AuthFeedback(state)
        TextButton(onClick = onBack, enabled = !state.isLoading) {
            Text(stringResource(Res.string.back_to_login))
        }
    }
}

@Composable
fun VerifyEmailScreen(viewModel: VerifyEmailViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VerifyEmailContent(
        state = state,
        onReload = viewModel::reloadUser,
        onResend = viewModel::resendVerification,
        onSignOut = viewModel::signOut,
    )
}

@Composable
internal fun VerifyEmailContent(
    state: VerifyEmailUiState,
    onReload: () -> Unit,
    onResend: () -> Unit,
    onSignOut: () -> Unit,
) {
    AuthLayout(Res.string.verify_email_title, Res.string.verify_email_message, AuthUiTags.VERIFY_EMAIL) {
        state.email?.let {
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
        JaiqalButton(
            text = stringResource(Res.string.check_verification),
            onClick = onReload,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
        )
        TextButton(onClick = onResend, enabled = !state.isLoading) {
            Text(stringResource(Res.string.resend_verification))
        }
        if (state.isLoading) CircularProgressIndicator()
        state.error?.let { AuthErrorText(it) }
        if (state.message == AuthMessage.VERIFICATION_EMAIL_SENT) {
            Text(
                stringResource(Res.string.verification_email_sent),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onSignOut, enabled = !state.isLoading) {
            Text(stringResource(Res.string.sign_out))
        }
    }
}

@Composable
private fun AuthFields(
    state: AuthFormUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
) {
    JaiqalTextField(
        value = state.email,
        onValueChange = onEmailChange,
        label = stringResource(Res.string.email),
        modifier = Modifier.testTag(AuthUiTags.EMAIL),
        enabled = !state.isLoading,
        isError = state.error == AuthErrorCode.INVALID_EMAIL,
    )
    if (showPassword) {
        JaiqalTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(Res.string.password),
            modifier = Modifier.testTag(AuthUiTags.PASSWORD),
            enabled = !state.isLoading,
            isError = state.error == AuthErrorCode.WEAK_PASSWORD,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

@Composable
private fun AuthFeedback(state: AuthFormUiState) {
    if (state.isLoading) CircularProgressIndicator()
    state.error?.let { AuthErrorText(it) }
    if (state.message == AuthMessage.RESET_EMAIL_SENT) {
        Text(
            stringResource(Res.string.reset_email_sent),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AuthErrorText(error: AuthErrorCode) {
    val resource = when (error) {
        AuthErrorCode.INVALID_EMAIL -> Res.string.auth_error_invalid_email
        AuthErrorCode.INVALID_CREDENTIALS -> Res.string.auth_error_invalid_credentials
        AuthErrorCode.EMAIL_ALREADY_IN_USE -> Res.string.auth_error_email_in_use
        AuthErrorCode.WEAK_PASSWORD -> Res.string.auth_error_weak_password
        AuthErrorCode.USER_DISABLED -> Res.string.auth_error_user_disabled
        AuthErrorCode.TOO_MANY_REQUESTS -> Res.string.auth_error_too_many_requests
        AuthErrorCode.NETWORK -> Res.string.auth_error_network
        AuthErrorCode.NO_CURRENT_USER -> Res.string.auth_error_no_user
        AuthErrorCode.NOT_CONFIGURED -> Res.string.auth_error_not_configured
        AuthErrorCode.UNKNOWN -> Res.string.auth_error_unknown
    }
    Text(
        text = stringResource(resource),
        modifier = Modifier.testTag(AuthUiTags.ERROR),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun AuthLayout(
    title: StringResource,
    message: StringResource,
    testTag: String = AuthUiTags.LOGIN,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag)
            .verticalScroll(rememberScrollState())
            .padding(JaiqalTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(JaiqalTheme.spacing.large))
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(JaiqalTheme.spacing.large))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.small),
            content = { content() },
        )
    }
}
