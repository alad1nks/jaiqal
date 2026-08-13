package com.alad1nks.jaiqal.feature.auth.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.runtime.mutableStateOf
import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AuthScreenTest {
    @Test
    fun loginUsesStableSemanticsAndForwardsCredentials() = runComposeUiTest {
        val email = mutableStateOf("")
        val password = mutableStateOf("")
        var submits = 0
        setContent {
            JaiqalTheme {
                LoginContent(
                    state = AuthFormUiState(email = email.value, password = password.value),
                    onEmailChange = { email.value = it },
                    onPasswordChange = { password.value = it },
                    onSubmit = { submits++ },
                    onRegister = {},
                    onForgotPassword = {},
                )
            }
        }

        onNodeWithTag(AuthUiTags.LOGIN).assertIsDisplayed()
        onNodeWithTag(AuthUiTags.EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AuthUiTags.PASSWORD).performTextInput("secret")
        onNodeWithTag(AuthUiTags.SUBMIT).performClick()

        assertEquals("owner@example.com", email.value)
        assertEquals("secret", password.value)
        assertEquals(1, submits)
    }

    @Test
    fun registerAndVerifyEmailExposeStateWithoutLocalizedSelectors() = runComposeUiTest {
        setContent {
            JaiqalTheme {
                RegisterContent(
                    state = AuthFormUiState(error = AuthErrorCode.WEAK_PASSWORD),
                    onEmailChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
        onNodeWithTag(AuthUiTags.REGISTER).assertIsDisplayed()
        onNodeWithTag(AuthUiTags.ERROR).assertIsDisplayed()

        setContent {
            JaiqalTheme {
                VerifyEmailContent(
                    state = VerifyEmailUiState(email = "owner@example.com"),
                    onReload = {},
                    onResend = {},
                    onSignOut = {},
                )
            }
        }
        onNodeWithTag(AuthUiTags.VERIFY_EMAIL).assertIsDisplayed()
    }
}
