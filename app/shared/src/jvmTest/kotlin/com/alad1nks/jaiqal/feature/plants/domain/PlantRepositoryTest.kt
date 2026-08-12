package com.alad1nks.jaiqal.feature.plants.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.api.contract.CreatePlantRequest
import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryPoint
import com.alad1nks.jaiqal.api.contract.PlantHistoryResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.UpdatePlantRequest
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.OfflineMutationException
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.core.cache.SqlDelightOfflineCache
import com.alad1nks.jaiqal.core.cache.SyncCoordinator
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.plants.data.PlantRemoteDataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlantRepositoryTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(JaiqalDatabase.Schema::create)
    private val cache = SqlDelightOfflineCache(JaiqalDatabase(driver))
    private val session = UserSessionStore().apply {
        set(CurrentUserResponse(ACCOUNT_ID, "plant@example.com", true))
    }
    private val remote = FakePlantRemoteDataSource()
    private val repository = OfflineFirstPlantRepository(
        remote = remote,
        realtime = { emptyFlow() },
        cache = cache,
        userSessionStore = session,
        syncCoordinator = SyncCoordinator(),
        now = { NOW },
    )

    @AfterTest
    fun close() = driver.close()

    @Test
    fun cachedListIsAvailableBeforeFailedRefreshAndIsNotDeleted() = runTest {
        cache.replacePlants(ACCOUNT_ID, listOf(plant("cached", "Cached Aloe")))
        remote.listFailure = ApiException.Connectivity(IllegalStateException("offline"))

        assertEquals("Cached Aloe", repository.observePlants().first().single().plant.name)
        assertIs<RefreshResult.PreservedCache>(repository.refreshPlants())
        assertEquals("Cached Aloe", repository.observePlants().first().single().plant.name)
    }

    @Test
    fun refreshCombinesServerPlantsDevicesTelemetryAndActiveAlerts() = runTest {
        remote.plants = listOf(plant("plant-a", "Aloe"))
        remote.devices = listOf(DeviceResponse("device-a", "plant-a", "Sensor"))
        remote.latestByPlant["plant-a"] = latest("plant-a", "device-a")
        remote.alertsByPlant["plant-a"] = listOf(
            alert("active", AlertStatus.ACTIVE),
            alert("recovered", AlertStatus.RECOVERED),
        )

        assertEquals(RefreshResult.Updated, repository.refreshPlants())
        val item = repository.observePlants().first().single()

        assertEquals("plant-a", item.plant.id)
        assertEquals("device-a", item.device?.id)
        assertEquals(42.0, item.latest?.soilMoisturePercent)
        assertEquals(listOf("active"), item.activeAlerts.map { it.id })
        assertEquals(NOW.toString(), cache.observeMetadata(ACCOUNT_ID, "plants").first()?.syncedAt)
    }

    @Test
    fun createIsServerFirstAndCachesReturnedServerIdentifier() = runTest {
        remote.createdPlant = plant("server-generated-id", "Aloe")

        val created = repository.createPlant("Aloe", null, null)

        assertEquals("server-generated-id", created.id)
        assertEquals("server-generated-id", cache.observePlants(ACCOUNT_ID).first().single().id)
        assertEquals(CreatePlantRequest("Aloe", null, null), remote.lastCreateRequest)
    }

    @Test
    fun offlineCreateDoesNotAddOptimisticPlant() = runTest {
        remote.createFailure = ApiException.Connectivity(IllegalStateException("offline"))

        assertFailsWith<OfflineMutationException> { repository.createPlant("Aloe", null, null) }

        assertTrue(cache.observePlants(ACCOUNT_ID).first().isEmpty())
    }

    @Test
    fun detailsRefreshStoresSelectedHistoryRange() = runTest {
        remote.plants = listOf(plant("plant-a", "Aloe"))
        remote.historyResponse = PlantHistoryResponse(
            "plant-a",
            HistoryInterval.FIVE_MINUTES,
            listOf(PlantHistoryPoint("2026-07-29T00:00:00Z", soilMoisturePercent = 41.0)),
        )
        val key = repository.lastDayHistoryKey("plant-a")

        assertEquals(RefreshResult.Updated, repository.refreshPlant("plant-a", key))

        assertEquals(41.0, repository.observePlant("plant-a", key).first()?.history?.points?.single()?.soilMoisturePercent)
    }

    @Test
    fun historyRangesUseServerAggregationAppropriateForTheirSize() {
        assertEquals(HistoryInterval.FIVE_MINUTES, repository.historyKey("plant-a", HistoryRange.LAST_24_HOURS).interval)
        assertEquals(HistoryInterval.ONE_HOUR, repository.historyKey("plant-a", HistoryRange.LAST_7_DAYS).interval)
        assertEquals(HistoryInterval.ONE_DAY, repository.historyKey("plant-a", HistoryRange.LAST_30_DAYS).interval)
    }

    @Test
    fun realtimeRefreshUpdatesLatestAndSelectedHistoryCache() = runTest {
        remote.latestByPlant["plant-a"] = latest("plant-a", "device-a").copy(soilMoisturePercent = 55.0)
        remote.historyResponse = PlantHistoryResponse(
            "plant-a",
            HistoryInterval.ONE_HOUR,
            listOf(PlantHistoryPoint("2026-07-29T11:00:00Z", soilMoisturePercent = 55.0)),
        )
        val key = repository.historyKey("plant-a", HistoryRange.LAST_7_DAYS)

        assertEquals(RefreshResult.Updated, repository.refreshRealtimeState("plant-a", key))

        assertEquals(55.0, cache.observeLatestStates(ACCOUNT_ID).first().single().soilMoisturePercent)
        assertEquals(55.0, cache.observeHistory(key).first()?.points?.single()?.soilMoisturePercent)
    }

    @Test
    fun logoutCancelsActiveRealtimeStream() = runTest {
        var streamStarted = false
        var streamCancelled = false
        val realtimeRepository = OfflineFirstPlantRepository(
            remote = remote,
            realtime = {
                flow {
                    streamStarted = true
                    try {
                        awaitCancellation()
                    } finally {
                        streamCancelled = true
                    }
                }
            },
            cache = cache,
            userSessionStore = session,
            syncCoordinator = SyncCoordinator(),
            now = { NOW },
        )
        val collection = launch { realtimeRepository.realtimeMeasurements("plant-a").collect {} }
        advanceUntilIdle()

        session.clear()
        advanceUntilIdle()

        assertTrue(streamStarted)
        assertTrue(streamCancelled)
        collection.cancel()
    }

    private class FakePlantRemoteDataSource : PlantRemoteDataSource {
        var plants = emptyList<PlantResponse>()
        var devices = emptyList<DeviceResponse>()
        val latestByPlant = mutableMapOf<String, PlantLatestResponse>()
        val alertsByPlant = mutableMapOf<String, List<AlertEventResponse>>()
        var historyResponse: PlantHistoryResponse? = null
        var createdPlant = plant("created", "Plant")
        var listFailure: Throwable? = null
        var createFailure: Throwable? = null
        var lastCreateRequest: CreatePlantRequest? = null

        override suspend fun listPlants(): List<PlantResponse> {
            listFailure?.let { throw it }
            return plants
        }

        override suspend fun getPlant(plantId: String) = plants.first { it.id == plantId }
        override suspend fun createPlant(request: CreatePlantRequest): PlantResponse {
            createFailure?.let { throw it }
            lastCreateRequest = request
            return createdPlant
        }
        override suspend fun updatePlant(plantId: String, request: UpdatePlantRequest) =
            plants.first { it.id == plantId }.copy(
                name = request.name ?: plants.first { it.id == plantId }.name,
                species = request.species,
                imageUrl = request.imageUrl,
            )
        override suspend fun listDevices() = devices
        override suspend fun latest(plantId: String) = latestByPlant[plantId]
            ?: throw ApiException.Backend(404, "NOT_FOUND", null)
        override suspend fun history(plantId: String, from: String, to: String, interval: HistoryInterval) =
            historyResponse ?: PlantHistoryResponse(plantId, interval, emptyList())
        override suspend fun alerts(plantId: String) = alertsByPlant[plantId].orEmpty()
    }

    private companion object {
        const val ACCOUNT_ID = "account-a"
        val NOW = Instant.parse("2026-07-29T12:00:00Z")
        fun plant(id: String, name: String) = PlantResponse(id, name, null, null, "2026-07-29T00:00:00Z")
        fun latest(plantId: String, deviceId: String) = PlantLatestResponse(
            plantId, deviceId, "2026-07-29T11:59:00Z", "2026-07-29T11:59:01Z",
            42.0, 500, 23.0, 51.0, 700, true, true,
        )
        fun alert(id: String, status: AlertStatus) = AlertEventResponse(
            id, AlertType.LOW_SOIL_MOISTURE, status,
            "2026-07-29T10:00:00Z", lastObservedAt = "2026-07-29T11:00:00Z",
        )
    }
}
