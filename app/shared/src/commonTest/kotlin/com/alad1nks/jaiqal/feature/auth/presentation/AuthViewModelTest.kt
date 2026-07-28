package com.alad1nks.jaiqal.feature.auth.presentation

import com.alad1nks.jaiqal.core.auth.AuthErrorCode
import com.alad1nks.jaiqal.core.auth.FakeAuthProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
}
