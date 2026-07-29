package com.alad1nks.jaiqal.core.cache

import com.alad1nks.jaiqal.core.network.ApiException
import kotlinx.coroutines.CancellationException

enum class ReadStrategy {
    CACHE_FIRST_THEN_REFRESH,
    CACHE_FIRST_THEN_STREAM,
    NETWORK_FIRST_WITH_CACHE_FALLBACK,
}

enum class WriteStrategy {
    SERVER_FIRST_THEN_CACHE,
}

data class SyncPolicy(
    val readStrategy: ReadStrategy? = null,
    val writeStrategy: WriteStrategy? = null,
)

object SyncPolicies {
    val plantList = SyncPolicy(readStrategy = ReadStrategy.CACHE_FIRST_THEN_REFRESH)
    val plantDetails = SyncPolicy(readStrategy = ReadStrategy.CACHE_FIRST_THEN_REFRESH)
    val latestMeasurement = SyncPolicy(readStrategy = ReadStrategy.CACHE_FIRST_THEN_STREAM)
    val history = SyncPolicy(readStrategy = ReadStrategy.NETWORK_FIRST_WITH_CACHE_FALLBACK)
    val alerts = SyncPolicy(readStrategy = ReadStrategy.CACHE_FIRST_THEN_REFRESH)
    val mutation = SyncPolicy(writeStrategy = WriteStrategy.SERVER_FIRST_THEN_CACHE)
}

class OfflineMutationException(cause: Throwable) : Exception(
    "Changes require a backend connection and were not saved locally",
    cause,
)

sealed interface RefreshResult {
    data object Updated : RefreshResult
    data class PreservedCache(val cause: Throwable) : RefreshResult
}

class SyncCoordinator {
    suspend fun <T> refreshPreservingCache(
        fetchFromServer: suspend () -> T,
        replaceCache: suspend (T) -> Unit,
    ): RefreshResult = try {
        replaceCache(fetchFromServer())
        RefreshResult.Updated
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        RefreshResult.PreservedCache(failure)
    }

    suspend fun <T> networkFirstWithCacheFallback(
        fetchFromServer: suspend () -> T,
        replaceCache: suspend (T) -> Unit,
        readCache: suspend () -> T?,
    ): T {
        val serverResult = try {
            fetchFromServer()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return readCache() ?: throw failure
        }
        try {
            replaceCache(serverResult)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Return the authoritative server value; a later refresh can repair the cache.
        }
        return serverResult
    }

    suspend fun <T> serverFirstMutation(
        mutateServer: suspend () -> T,
        updateCache: suspend (T) -> Unit,
    ): T {
        val result = try {
            mutateServer()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ApiException.Connectivity) {
            throw OfflineMutationException(failure)
        } catch (failure: ApiException.Timeout) {
            throw OfflineMutationException(failure)
        }
        try {
            updateCache(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The server mutation succeeded; a later refresh can repair a failed local write.
        }
        return result
    }
}
