package com.alad1nks.jaiqal.app

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.FakeAuthProvider
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun restoredVerifiedFirebaseSessionIsSynchronizedWithBackend() = runTest(dispatcher) {
        val auth = FakeAuthProvider(AuthState.Authenticated("plant@example.com", emailVerified = true))
        val store = UserSessionStore()
        var backendCalls = 0
        val gateway = CurrentUserGateway {
            backendCalls += 1
            CurrentUserResponse("internal-user-id", "plant@example.com", emailVerified = true)
        }

        val viewModel = AppViewModel(auth, gateway, store, SessionErrorStore())
        advanceUntilIdle()

        assertEquals(1, backendCalls)
        assertEquals(SessionState.AUTHENTICATED, viewModel.state.value.session)
        assertEquals("internal-user-id", store.session.value?.userId)
    }

    @Test
    fun unverifiedEmailDoesNotCallBackend() = runTest(dispatcher) {
        val auth = FakeAuthProvider(AuthState.Authenticated("plant@example.com", emailVerified = false))
        val store = UserSessionStore()
        var backendCalls = 0
        val viewModel = AppViewModel(auth, CurrentUserGateway {
            backendCalls += 1
            error("Backend must not be called")
        }, store, SessionErrorStore())

        advanceUntilIdle()

        assertEquals(SessionState.EMAIL_VERIFICATION_REQUIRED, viewModel.state.value.session)
        assertEquals(0, backendCalls)
        assertNull(store.session.value)
    }

    @Test
    fun logoutClearsInternalUserState() = runTest(dispatcher) {
        val auth = FakeAuthProvider(AuthState.Authenticated("plant@example.com", emailVerified = true))
        val store = UserSessionStore()
        val viewModel = AppViewModel(auth, CurrentUserGateway {
            CurrentUserResponse("internal-user-id", "plant@example.com", emailVerified = true)
        }, store, SessionErrorStore())
        advanceUntilIdle()

        auth.signOut()
        advanceUntilIdle()

        assertEquals(SessionState.UNAUTHENTICATED, viewModel.state.value.session)
        assertNull(store.session.value)
    }

    @Test
    fun cancelledBackendSynchronizationDoesNotBecomeSessionError() = runTest(dispatcher) {
        val auth = FakeAuthProvider(AuthState.Authenticated("plant@example.com", emailVerified = true))
        val store = UserSessionStore()
        val viewModel = AppViewModel(
            auth,
            CurrentUserGateway { kotlinx.coroutines.awaitCancellation() },
            store,
            SessionErrorStore(),
        )
        runCurrent()

        auth.signOut()
        advanceUntilIdle()

        assertEquals(SessionState.UNAUTHENTICATED, viewModel.state.value.session)
        assertNull(store.session.value)
    }
}
