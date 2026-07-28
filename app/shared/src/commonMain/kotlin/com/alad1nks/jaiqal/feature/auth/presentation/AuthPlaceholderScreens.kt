package com.alad1nks.jaiqal.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalTextField
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.app_name
import jaiqal.app.shared.generated.resources.back_to_login
import jaiqal.app.shared.generated.resources.email
import jaiqal.app.shared.generated.resources.forgot_password
import jaiqal.app.shared.generated.resources.forgot_password_message
import jaiqal.app.shared.generated.resources.login_message
import jaiqal.app.shared.generated.resources.login_title
import jaiqal.app.shared.generated.resources.password
import jaiqal.app.shared.generated.resources.register
import jaiqal.app.shared.generated.resources.register_message
import jaiqal.app.shared.generated.resources.sign_in
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoginPlaceholderScreen(onRegister: () -> Unit, onForgotPassword: () -> Unit) {
    AuthLayout(Res.string.login_title, Res.string.login_message) {
        JaiqalTextField("", {}, stringResource(Res.string.email), enabled = false)
        JaiqalTextField("", {}, stringResource(Res.string.password), enabled = false)
        JaiqalButton(stringResource(Res.string.sign_in), {}, Modifier.fillMaxWidth(), enabled = false)
        TextButton(onClick = onForgotPassword) { Text(stringResource(Res.string.forgot_password)) }
        TextButton(onClick = onRegister) { Text(stringResource(Res.string.register)) }
    }
}

@Composable
fun RegisterPlaceholderScreen(onBack: () -> Unit) {
    AuthLayout(Res.string.register, Res.string.register_message) {
        JaiqalTextField("", {}, stringResource(Res.string.email), enabled = false)
        JaiqalTextField("", {}, stringResource(Res.string.password), enabled = false)
        JaiqalButton(stringResource(Res.string.register), {}, Modifier.fillMaxWidth(), enabled = false)
        TextButton(onClick = onBack) { Text(stringResource(Res.string.back_to_login)) }
    }
}

@Composable
fun ForgotPasswordPlaceholderScreen(onBack: () -> Unit) {
    AuthLayout(Res.string.forgot_password, Res.string.forgot_password_message) {
        JaiqalTextField("", {}, stringResource(Res.string.email), enabled = false)
        TextButton(onClick = onBack) { Text(stringResource(Res.string.back_to_login)) }
    }
}

@Composable
private fun AuthLayout(title: StringResource, message: StringResource, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(JaiqalTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(JaiqalTheme.spacing.large))
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(JaiqalTheme.spacing.large))
        Column(verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.small), content = { content() })
    }
}
