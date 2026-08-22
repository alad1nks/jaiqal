package com.alad1nks.jaiqal.feature.auth.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    const val DIVIDER = "auth.divider"
    const val GOOGLE = "auth.google"
    const val APPLE = "auth.apple"
    const val GOOGLE_LOADING = "auth.google.loading"
    const val APPLE_LOADING = "auth.apple.loading"
    const val FORGOT_PASSWORD = "auth.forgot-password"
    const val REGISTER_ACTION = "auth.register-action"
    const val ERROR = "auth.error"
}

@Composable
fun LoginScreen(
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(
        state = state,
        onEmailChange = viewModel::setEmail,
        onPasswordChange = viewModel::setPassword,
        onSubmit = viewModel::signIn,
        onGoogle = viewModel::signInWithGoogle,
        onApple = viewModel::signInWithApple,
        onRegister = onRegister,
        onForgotPassword = onForgotPassword,
    )
}

@Composable
internal fun LoginContent(
    state: AuthFormUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    onApple: () -> Unit,
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
        Text(
            text = stringResource(Res.string.auth_or),
            modifier = Modifier.testTag(AuthUiTags.DIVIDER),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        GoogleAuthButton(
            onClick = onGoogle,
            enabled = !state.isLoading,
            isLoading = state.loadingAction == AuthAction.GOOGLE,
        )
        AppleAuthButton(
            onClick = onApple,
            enabled = !state.isLoading,
            isLoading = state.loadingAction == AuthAction.APPLE,
        )
        AuthFeedback(state)
        TextButton(
            onClick = onForgotPassword,
            modifier = Modifier.testTag(AuthUiTags.FORGOT_PASSWORD),
            enabled = !state.isLoading,
        ) {
            Text(stringResource(Res.string.forgot_password))
        }
        TextButton(
            onClick = onRegister,
            modifier = Modifier.testTag(AuthUiTags.REGISTER_ACTION),
            enabled = !state.isLoading,
        ) {
            Text(stringResource(Res.string.register))
        }
    }
}

@Composable
private fun GoogleAuthButton(onClick: () -> Unit, enabled: Boolean, isLoading: Boolean) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(AuthUiTags.GOOGLE),
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF1F1F1F),
            disabledContainerColor = Color(0xFFF2F2F2),
            disabledContentColor = Color(0xFF6F6F6F),
        ),
    ) {
        if (isLoading) {
            ProviderProgress(AuthUiTags.GOOGLE_LOADING, Color(0xFF1A73E8))
        } else {
            Text("G", color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Text(stringResource(Res.string.continue_with_google), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AppleAuthButton(onClick: () -> Unit, enabled: Boolean, isLoading: Boolean) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(AuthUiTags.APPLE),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Black,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF3C3C3C),
            disabledContentColor = Color(0xFFBEBEBE),
        ),
    ) {
        if (isLoading) {
            ProviderProgress(AuthUiTags.APPLE_LOADING, Color.White)
            Spacer(Modifier.size(12.dp))
        }
        Text(stringResource(Res.string.continue_with_apple), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProviderProgress(testTag: String, color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp).testTag(testTag),
        color = color,
        strokeWidth = 2.dp,
    )
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
    when (state.loadingAction) {
        AuthAction.SIGN_IN, AuthAction.SIGN_UP, AuthAction.RESET_PASSWORD -> CircularProgressIndicator()
        AuthAction.GOOGLE, AuthAction.APPLE, null -> Unit
    }
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
        AuthErrorCode.CANCELLED -> Res.string.auth_error_cancelled
        AuthErrorCode.PROVIDER_UNAVAILABLE -> Res.string.auth_error_provider_unavailable
        AuthErrorCode.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL ->
            Res.string.auth_error_account_exists_with_different_credential
        AuthErrorCode.CREDENTIAL_ALREADY_IN_USE -> Res.string.auth_error_credential_already_in_use
        AuthErrorCode.INVALID_NONCE -> Res.string.auth_error_invalid_nonce
        AuthErrorCode.REAUTHENTICATION_REQUIRED -> Res.string.auth_error_invalid_credentials
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
