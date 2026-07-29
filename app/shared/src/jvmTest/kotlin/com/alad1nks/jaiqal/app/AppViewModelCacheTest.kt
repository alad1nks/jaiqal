package com.alad1nks.jaiqal.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.core.auth.AuthState
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.FakeAuthProvider
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.SqlDelightOfflineCache
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelCacheTest {
    private val dispatcher = StandardTestDispatcher()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(JaiqalDatabase.Schema::create)
    private val cache = SqlDelightOfflineCache(JaiqalDatabase(driver))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    @Test
    fun backendUserIsCachedAndLogoutDeletesOnlyThatAccountData() = runTest(dispatcher) {
        val auth = FakeAuthProvider(AuthState.Authenticated("plant@example.com", emailVerified = true))
        val viewModel = AppViewModel(
            authProvider = auth,
            currentUserGateway = CurrentUserGateway {
                CurrentUserResponse("account-a", "plant@example.com", emailVerified = true)
            },
            userSessionStore = UserSessionStore(),
            sessionErrorStore = SessionErrorStore(),
            offlineCache = cache,
        )
        advanceUntilIdle()

        assertEquals(SessionState.AUTHENTICATED, viewModel.state.value.session)
        assertEquals("account-a", cache.observeUser("account-a").first()?.id)

        auth.signOut()
        advanceUntilIdle()

        assertEquals(SessionState.UNAUTHENTICATED, viewModel.state.value.session)
        assertNull(cache.observeUser("account-a").first())
    }
}
