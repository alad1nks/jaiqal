package com.alad1nks.jaiqal.feature.settings.presentation

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.api.contract.DeleteAccountResponse
import com.alad1nks.jaiqal.core.auth.AccountAuthMethod
import com.alad1nks.jaiqal.core.auth.AccountDeletionGateway
import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.AuthException
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.AccountDeletionCoordinator
import com.alad1nks.jaiqal.core.auth.FederatedAuthMethod
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.NoOpOfflineCache
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

    @Test
    fun deletionRequiresConfirmationAndReauthenticatesWithCurrentPassword() = runTest(dispatcher) {
        val auth = FakeAuth(AccountAuthMethod.PASSWORD)
        val session = UserSessionStore().apply {
            set(CurrentUserResponse("account-id", "test@example.com", true))
        }
        var backendDeletes = 0
        val viewModel = viewModel(auth = auth, session = session) {
            backendDeletes += 1
            DeleteAccountResponse()
        }
        advanceUntilIdle()

        viewModel.requestAccountDeletion()
        assertTrue(viewModel.state.value.showDeleteConfirmation)
        viewModel.confirmAccountDeletion("current-password")
        advanceUntilIdle()

        assertEquals(listOf<String?>("current-password"), auth.reauthenticationPasswords)
        assertEquals(1, backendDeletes)
        assertEquals(1, auth.deletedUsers)
        assertFalse(viewModel.state.value.showDeleteConfirmation)
        assertNull(session.session.value)
    }

    private fun viewModel(
        isDebug: Boolean = true,
        auth: FakeAuth,
        session: UserSessionStore = UserSessionStore(),
        deletionGateway: AccountDeletionGateway = AccountDeletionGateway { DeleteAccountResponse() },
    ) = SettingsViewModel(
        DefaultBackendConfig(AppEnvironment.LOCAL, "http://localhost:8080"),
        AppInfo("1.0", "Test", isDebug, null),
        auth,
        session,
        AccountDeletionCoordinator(
            deletionGateway,
            auth,
            NoOpOfflineCache,
            session,
        ),
    )

    private class FakeAuth(method: AccountAuthMethod = AccountAuthMethod.UNKNOWN) : AuthProvider {
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            AuthState.Authenticated("test@example.com", true, method),
        )
        var verificationFailure: Throwable? = null
        val reauthenticationPasswords = mutableListOf<String?>()
        var deletedUsers = 0
        override suspend fun sendEmailVerification() { verificationFailure?.let { throw it } }
        override suspend fun signOut() = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signIn(method: FederatedAuthMethod) = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun reloadUser() = Unit
        override suspend fun getIdToken(forceRefresh: Boolean): String? = null
        override suspend fun reauthenticateForAccountDeletion(password: String?) {
            reauthenticationPasswords += password
        }
        override suspend fun deleteCurrentUser() {
            deletedUsers += 1
        }
    }
}
