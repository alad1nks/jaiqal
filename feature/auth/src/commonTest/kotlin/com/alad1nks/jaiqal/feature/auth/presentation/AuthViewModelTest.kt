package com.alad1nks.jaiqal.feature.auth.presentation

import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.AuthException
import com.alad1nks.jaiqal.core.auth.FakeAuthProvider
import com.alad1nks.jaiqal.core.auth.FederatedAuthMethod
import com.alad1nks.jaiqal.core.auth.AuthState
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
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun validCredentialsAreSentOnlyToAuthProvider() = runTest(dispatcher) {
        val auth = FakeAuthProvider()
        val viewModel = AuthViewModel(auth)
        viewModel.setEmail(" plant@example.com ")
        viewModel.setPassword("firebase-password")

        viewModel.signIn()
        advanceUntilIdle()

        assertEquals("plant@example.com", auth.lastEmail)
        assertEquals("firebase-password", auth.lastPassword)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun rejectsInvalidEmailAndFirebaseMinimumPasswordLocally() = runTest(dispatcher) {
        val auth = FakeAuthProvider()
        val viewModel = AuthViewModel(auth)
        viewModel.setEmail("not-an-email")
        viewModel.setPassword("12345")

        viewModel.signUp()
        advanceUntilIdle()

        assertEquals(AuthErrorCode.INVALID_EMAIL, viewModel.state.value.error)
        assertNull(auth.lastEmail)
    }

    @Test
    fun registrationUsesValidatedCredentialsAndStartsEmailVerification() = runTest(dispatcher) {
        val auth = FakeAuthProvider()
        val viewModel = AuthViewModel(auth)
        viewModel.setEmail("owner@example.com")
        viewModel.setPassword("secret-password")

        viewModel.signUp()
        advanceUntilIdle()

        assertEquals("owner@example.com", auth.lastEmail)
        assertEquals("secret-password", auth.lastPassword)
        assertEquals(1, auth.verificationEmailsSent)
    }

    @Test
    fun validEmailWithWeakPasswordIsRejectedBeforeRegistration() = runTest(dispatcher) {
        val auth = FakeAuthProvider()
        val viewModel = AuthViewModel(auth)
        viewModel.setEmail("owner@example.com")
        viewModel.setPassword("12345")

        viewModel.signUp()
        advanceUntilIdle()

        assertEquals(AuthErrorCode.WEAK_PASSWORD, viewModel.state.value.error)
        assertNull(auth.lastEmail)
    }

    @Test
    fun federatedActionTracksProviderAndBlocksDuplicateTaps() = runTest(dispatcher) {
        val auth = FakeAuthProvider().apply { federatedSignInDelayMillis = 1_000 }
        val viewModel = AuthViewModel(auth)

        viewModel.signInWithGoogle()
        assertEquals(AuthAction.GOOGLE, viewModel.state.value.loadingAction)

        viewModel.signInWithApple()
        runCurrent()
        advanceUntilIdle()

        assertEquals(FederatedAuthMethod.GOOGLE, auth.lastFederatedAuthMethod)
        assertNull(viewModel.state.value.loadingAction)
    }

    @Test
    fun providerCancellationIsNotShownAsCriticalError() = runTest(dispatcher) {
        val auth = FakeAuthProvider().apply {
            federatedFailure = AuthException(AuthErrorCode.CANCELLED)
        }
        val viewModel = AuthViewModel(auth)

        viewModel.signInWithApple()
        advanceUntilIdle()

        assertEquals(FederatedAuthMethod.APPLE, auth.lastFederatedAuthMethod)
        assertNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.loadingAction)
    }

    @Test
    fun emailCollisionIsShownWithoutAutomaticProviderSwitchOrAccountMerge() = runTest(dispatcher) {
        val auth = FakeAuthProvider().apply {
            federatedFailure = AuthException(AuthErrorCode.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL)
        }
        val viewModel = AuthViewModel(auth)

        viewModel.signInWithGoogle()
        advanceUntilIdle()

        assertEquals(
            AuthErrorCode.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
            viewModel.state.value.error,
        )
        assertEquals(listOf(FederatedAuthMethod.GOOGLE), auth.federatedAuthMethods)
        assertEquals(AuthState.Unauthenticated, auth.authState.value)
        assertNull(viewModel.state.value.loadingAction)
    }
}
