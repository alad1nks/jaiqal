package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.api.contract.DeleteAccountResponse
import com.alad1nks.jaiqal.core.cache.OfflineCache
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface CurrentUserGateway {
    suspend fun fetchCurrentUser(): CurrentUserResponse
}

class ApiCurrentUserGateway(
    private val apiClient: ApiClient,
) : CurrentUserGateway {
    override suspend fun fetchCurrentUser(): CurrentUserResponse = apiClient.request(
        path = "/api/v1/auth/me",
        serializer = CurrentUserResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Get
    }
}

class UnavailableCurrentUserGateway : CurrentUserGateway {
    override suspend fun fetchCurrentUser(): CurrentUserResponse =
        error("Backend synchronization is unavailable on this platform")
}

fun interface AccountDeletionGateway {
    suspend fun deleteAccount(): DeleteAccountResponse
}

class ApiAccountDeletionGateway(private val apiClient: ApiClient) : AccountDeletionGateway {
    override suspend fun deleteAccount(): DeleteAccountResponse = apiClient.request(
        path = "/api/v1/auth/me",
        serializer = DeleteAccountResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Delete
    }
}

class UnavailableAccountDeletionGateway : AccountDeletionGateway {
    override suspend fun deleteAccount(): DeleteAccountResponse =
        error("Account deletion is unavailable on this platform")
}

interface AccountDeletionRecoveryStore {
    suspend fun pendingAccountId(): String?
    suspend fun markPending(accountId: String)
    suspend fun clearPending()
}

class SqlDelightAccountDeletionRecoveryStore(database: JaiqalDatabase) : AccountDeletionRecoveryStore {
    private val queries = database.cacheMetadataQueries

    override suspend fun pendingAccountId(): String? =
        queries.selectAppPreference(RECOVERY_KEY).executeAsOneOrNull()

    override suspend fun markPending(accountId: String) {
        queries.upsertAppPreference(RECOVERY_KEY, accountId)
    }

    override suspend fun clearPending() {
        queries.deleteAppPreference(RECOVERY_KEY)
    }

    private companion object {
        const val RECOVERY_KEY = "account_deletion_pending"
    }
}

class InMemoryAccountDeletionRecoveryStore : AccountDeletionRecoveryStore {
    private var accountId: String? = null
    override suspend fun pendingAccountId(): String? = accountId
    override suspend fun markPending(accountId: String) { this.accountId = accountId }
    override suspend fun clearPending() { accountId = null }
}

class AccountDeletionCoordinator(
    private val gateway: AccountDeletionGateway,
    private val authProvider: AuthProvider,
    private val offlineCache: OfflineCache,
    private val userSessionStore: UserSessionStore,
    private val recoveryStore: AccountDeletionRecoveryStore = InMemoryAccountDeletionRecoveryStore(),
) {
    private val mutex = Mutex()

    suspend fun deleteAccount(password: String?) = mutex.withLock {
        val accountId = recoveryStore.pendingAccountId() ?: userSessionStore.session.value?.userId
            ?: throw AuthException(AuthErrorCode.NO_CURRENT_USER)

        authProvider.reauthenticateForAccountDeletion(password)
        recoveryStore.markPending(accountId)
        finishDeletion(accountId)
    }

    suspend fun recoverPendingDeletion(): Boolean = mutex.withLock {
        val accountId = recoveryStore.pendingAccountId() ?: return@withLock false
        finishDeletion(accountId)
        true
    }

    private suspend fun finishDeletion(accountId: String) {
        check(gateway.deleteAccount().deleted)
        offlineCache.clearAccount(accountId)
        authProvider.deleteCurrentUser()
        userSessionStore.clear()
        recoveryStore.clearPending()
    }
}
