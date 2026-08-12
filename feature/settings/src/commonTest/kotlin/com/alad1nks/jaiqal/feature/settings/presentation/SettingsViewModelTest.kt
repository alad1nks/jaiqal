package com.alad1nks.jaiqal.feature.settings.presentation

import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.AuthException
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.config.AppInfo
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() = Dispatchers.resetMain()

    @Test
    fun diagnosticsAreAvailableOnlyInDebug() = runTest(dispatcher) {
        val release = viewModel(isDebug = false, auth = FakeAuth())
        val debug = viewModel(isDebug = true, auth = FakeAuth())
        advanceUntilIdle()

        assertNull(release.state.value.diagnostics)
        assertNotNull(debug.state.value.diagnostics)
    }

    @Test
    fun verificationReportsSuccessAndMappedFailure() = runTest(dispatcher) {
        val auth = FakeAuth()
        val viewModel = viewModel(isDebug = true, auth = auth)

        viewModel.resendVerificationEmail()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.verificationSent)

        auth.verificationFailure = AuthException(AuthErrorCode.NETWORK)
        viewModel.resendVerificationEmail()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.verificationSent)
        assertEquals(SettingsUiError.NETWORK, viewModel.state.value.error)
    }

    private fun viewModel(isDebug: Boolean, auth: FakeAuth) = SettingsViewModel(
        DefaultBackendConfig(AppEnvironment.LOCAL, "http://localhost:8080"),
        AppInfo("1.0", "Test", isDebug, null),
        auth,
        UserSessionStore(),
    )

    private class FakeAuth : AuthProvider {
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            AuthState.Authenticated("test@example.com", true),
        )
        var verificationFailure: Throwable? = null
        override suspend fun sendEmailVerification() { verificationFailure?.let { throw it } }
        override suspend fun signOut() = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun reloadUser() = Unit
        override suspend fun getIdToken(forceRefresh: Boolean): String? = null
    }
}
