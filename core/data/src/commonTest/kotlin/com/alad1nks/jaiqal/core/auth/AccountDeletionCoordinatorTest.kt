package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.api.contract.DeleteAccountResponse
import com.alad1nks.jaiqal.core.cache.NoOpOfflineCache
import com.alad1nks.jaiqal.core.cache.OfflineCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AccountDeletionCoordinatorTest {
    @Test
    fun deletionReauthenticatesThenDeletesBackendCacheAndFirebaseUser() = runTest {
        val fixture = fixture()

        fixture.coordinator.deleteAccount("current-password")

        assertEquals(listOf<String?>("current-password"), fixture.auth.deletionReauthenticationPasswords)
        assertEquals(1, fixture.backendDeletes())
        assertEquals(listOf(ACCOUNT_ID), fixture.clearedAccounts)
        assertEquals(1, fixture.auth.firebaseUsersDeleted)
        assertNull(fixture.session.session.value)
    }

    @Test
    fun retryAfterFirebaseFailureSafelyRepeatsIdempotentBackendDeletion() = runTest {
        val fixture = fixture()
        fixture.auth.accountDeletionFailure = IllegalStateException("temporary Firebase failure")

        assertFailsWith<IllegalStateException> {
            fixture.coordinator.deleteAccount(null)
        }
        fixture.auth.accountDeletionFailure = null
        fixture.coordinator.deleteAccount(null)

        assertEquals(2, fixture.backendDeletes())
        assertEquals(listOf(ACCOUNT_ID, ACCOUNT_ID), fixture.clearedAccounts)
        assertEquals(listOf<String?>(null, null), fixture.auth.deletionReauthenticationPasswords)
        assertEquals(1, fixture.auth.firebaseUsersDeleted)
        assertNull(fixture.session.session.value)
    }

    @Test
    fun persistedMarkerResumesDeletionAfterCoordinatorRecreation() = runTest {
        val recovery = InMemoryAccountDeletionRecoveryStore()
        val fixture = fixture(recovery)
        fixture.auth.accountDeletionFailure = IllegalStateException("process stopped before Firebase deletion")
        assertFailsWith<IllegalStateException> { fixture.coordinator.deleteAccount(null) }

        fixture.auth.accountDeletionFailure = null
        val recreated = AccountDeletionCoordinator(
            gateway = AccountDeletionGateway {
                fixture.incrementBackendDeletes()
                DeleteAccountResponse()
            },
            authProvider = fixture.auth,
            offlineCache = fixture.cache,
            userSessionStore = fixture.session,
            recoveryStore = recovery,
        )

        assertEquals(true, recreated.recoverPendingDeletion())
        assertEquals(2, fixture.backendDeletes())
        assertEquals(listOf<String?>(null), fixture.auth.deletionReauthenticationPasswords)
        assertNull(fixture.session.session.value)
    }

    private fun fixture(
        recovery: AccountDeletionRecoveryStore = InMemoryAccountDeletionRecoveryStore(),
    ): Fixture {
        var backendDeletes = 0
        val auth = FakeAuthProvider(AuthState.Authenticated("owner@example.test", true))
        val session = UserSessionStore().apply {
            set(CurrentUserResponse(ACCOUNT_ID, "owner@example.test", true))
        }
        val clearedAccounts = mutableListOf<String>()
        val cache = object : OfflineCache by NoOpOfflineCache {
            override suspend fun clearAccount(accountId: String) {
                clearedAccounts += accountId
            }
        }
        val coordinator = AccountDeletionCoordinator(
            gateway = AccountDeletionGateway {
                backendDeletes += 1
                DeleteAccountResponse()
            },
            authProvider = auth,
            offlineCache = cache,
            userSessionStore = session,
            recoveryStore = recovery,
        )
        return Fixture(
            coordinator,
            auth,
            session,
            cache,
            clearedAccounts,
            { backendDeletes },
            { backendDeletes += 1 },
        )
    }

    private data class Fixture(
        val coordinator: AccountDeletionCoordinator,
        val auth: FakeAuthProvider,
        val session: UserSessionStore,
        val cache: OfflineCache,
        val clearedAccounts: List<String>,
        val backendDeletes: () -> Int,
        val incrementBackendDeletes: () -> Unit,
    )

    private companion object {
        const val ACCOUNT_ID = "account-id"
    }
}
