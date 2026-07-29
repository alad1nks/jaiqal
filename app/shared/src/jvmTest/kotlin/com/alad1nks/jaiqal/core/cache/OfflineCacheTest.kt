package com.alad1nks.jaiqal.core.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryPoint
import com.alad1nks.jaiqal.api.contract.PlantHistoryResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import com.alad1nks.jaiqal.core.network.ApiException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class OfflineCacheTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(JaiqalDatabase.Schema::create)
    private val cache = SqlDelightOfflineCache(JaiqalDatabase(driver))

    @AfterTest
    fun closeDriver() = driver.close()

    @Test
    fun cacheIsStrictlyScopedByInternalAccountId() = runTest {
        cache.replacePlants("account-a", listOf(plant("plant-a", "Aloe")))
        cache.replacePlants("account-b", listOf(plant("plant-b", "Basil")))
        cache.replaceDevices("account-a", listOf(device("device-a", "plant-a")))
        cache.replaceDevices("account-b", listOf(device("device-b", "plant-b")))

        assertEquals(listOf("plant-a"), cache.observePlants("account-a").first().map { it.id })
        assertEquals(listOf("plant-b"), cache.observePlants("account-b").first().map { it.id })
        assertEquals(listOf("device-a"), cache.observeDevices("account-a").first().map { it.id })
        assertEquals(listOf("device-b"), cache.observeDevices("account-b").first().map { it.id })
    }

    @Test
    fun clearAccountRemovesEveryOwnedCacheWithoutTouchingAnotherAccount() = runTest {
        seedEveryCacheType("account-a", "a")
        seedEveryCacheType("account-b", "b")

        cache.clearAccount("account-a")

        assertNull(cache.observeUser("account-a").first())
        assertTrue(cache.observePlants("account-a").first().isEmpty())
        assertTrue(cache.observeDevices("account-a").first().isEmpty())
        assertTrue(cache.observeLatestStates("account-a").first().isEmpty())
        assertNull(cache.observeHistory(historyKey("account-a", "a")).first())
        assertTrue(cache.observeAlertEvents("account-a", "plant-a").first().isEmpty())
        assertTrue(cache.observeAlertRules("account-a", "plant-a").first().isEmpty())
        assertNull(cache.observeMetadata("account-a", "plants").first())

        assertEquals("account-b", cache.observeUser("account-b").first()?.id)
        assertEquals("plant-b", cache.observePlants("account-b").first().single().id)
        assertEquals("device-b", cache.observeDevices("account-b").first().single().id)
        assertEquals("device-b", cache.observeLatestStates("account-b").first().single().deviceId)
        assertEquals(1, cache.observeHistory(historyKey("account-b", "b")).first()?.points?.size)
        assertEquals(1, cache.observeAlertEvents("account-b", "plant-b").first().size)
        assertEquals(1, cache.observeAlertRules("account-b", "plant-b").first().size)
        assertEquals("2026-07-29T00:00:00Z", cache.observeMetadata("account-b", "plants").first()?.syncedAt)
    }

    @Test
    fun failedRefreshPreservesPreviouslyValidCache() = runTest {
        val coordinator = SyncCoordinator()
        cache.replacePlants("account-a", listOf(plant("plant-a", "Cached Aloe")))

        val result = coordinator.refreshPreservingCache<List<PlantResponse>>(
            fetchFromServer = { throw ApiException.Connectivity(IllegalStateException("offline")) },
            replaceCache = { cache.replacePlants("account-a", it) },
        )

        assertIs<RefreshResult.PreservedCache>(result)
        assertEquals("Cached Aloe", cache.observePlants("account-a").first().single().name)
    }

    @Test
    fun historyUsesNetworkFirstAndFallsBackToSelectedCachedRange() = runTest {
        val coordinator = SyncCoordinator()
        val key = historyKey("account-a", "a")
        val cached = history("plant-a")
        cache.replaceHistory(key, cached)

        val result = coordinator.networkFirstWithCacheFallback(
            fetchFromServer = { throw ApiException.Timeout(IllegalStateException("timeout")) },
            replaceCache = { cache.replaceHistory(key, it) },
            readCache = { cache.observeHistory(key).first() },
        )

        assertEquals(cached, result)
    }

    @Test
    fun authoritativeNetworkResultIsReturnedEvenWhenCacheWriteFails() = runTest {
        val remote = plant("plant-a", "Fresh Aloe")

        val result = SyncCoordinator().networkFirstWithCacheFallback(
            fetchFromServer = { remote },
            replaceCache = { throw IllegalStateException("disk full") },
            readCache = { plant("plant-a", "Stale Aloe") },
        )

        assertEquals(remote, result)
    }

    @Test
    fun offlineMutationDoesNotCreateFalseCachedServerState() = runTest {
        val coordinator = SyncCoordinator()
        var cacheWrites = 0

        val failure = assertFailsWith<OfflineMutationException> {
            coordinator.serverFirstMutation(
                mutateServer = { throw ApiException.Connectivity(IllegalStateException("offline")) },
                updateCache = { cacheWrites += 1 },
            )
        }

        assertIs<ApiException.Connectivity>(failure.cause)
        assertEquals(0, cacheWrites)
    }

    @Test
    fun syncCoordinatorNeverConvertsCancellationToRefreshFailure() = runTest {
        assertFailsWith<CancellationException> {
            SyncCoordinator().refreshPreservingCache<Unit>(
                fetchFromServer = { throw CancellationException("cancelled") },
                replaceCache = {},
            )
        }
    }

    private suspend fun seedEveryCacheType(accountId: String, suffix: String) {
        val plantId = "plant-$suffix"
        cache.replaceUser(CurrentUserResponse(accountId, "$suffix@example.com", true))
        cache.replacePlants(accountId, listOf(plant(plantId, "Plant $suffix")))
        cache.replaceDevices(accountId, listOf(device("device-$suffix", plantId)))
        cache.replaceLatestState(accountId, latest(plantId, "device-$suffix"))
        cache.replaceHistory(historyKey(accountId, suffix), history(plantId))
        cache.replaceAlertEvents(accountId, plantId, listOf(alert("alert-$suffix")))
        cache.replaceAlertRules(accountId, plantId, listOf(rule("rule-$suffix")))
        cache.markSynced(accountId, "plants", "2026-07-29T00:00:00Z")
    }

    private fun plant(id: String, name: String) = PlantResponse(id, name, null, null, "2026-07-29T00:00:00Z")
    private fun device(id: String, plantId: String) = DeviceResponse(id, plantId, "Sensor", "1.0", null, 100, 900)
    private fun latest(plantId: String, deviceId: String) = PlantLatestResponse(
        plantId, deviceId, "2026-07-29T00:00:00Z", "2026-07-29T00:00:01Z",
        45.0, 500, 24.0, 50.0, 700, true, true,
    )
    private fun history(plantId: String) = PlantHistoryResponse(
        plantId,
        HistoryInterval.ONE_HOUR,
        listOf(PlantHistoryPoint("2026-07-29T00:00:00Z", soilMoisturePercent = 45.0)),
    )
    private fun historyKey(accountId: String, suffix: String) = HistoryCacheKey(
        accountId, "plant-$suffix", HistoryInterval.ONE_HOUR,
        "2026-07-28T00:00:00Z", "2026-07-29T00:00:00Z",
    )
    private fun alert(id: String) = AlertEventResponse(
        id, AlertType.LOW_SOIL_MOISTURE, AlertStatus.ACTIVE,
        "2026-07-29T00:00:00Z", lastObservedAt = "2026-07-29T00:01:00Z",
    )
    private fun rule(id: String) = AlertRuleResponse(
        id, AlertType.LOW_SOIL_MOISTURE, 20.0, 60, 30, true,
    )
}
