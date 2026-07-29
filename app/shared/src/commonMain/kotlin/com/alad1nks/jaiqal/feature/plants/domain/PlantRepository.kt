package com.alad1nks.jaiqal.feature.plants.domain

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.CreatePlantRequest
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.UpdatePlantRequest
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.HistoryCacheKey
import com.alad1nks.jaiqal.core.cache.OfflineCache
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.core.cache.SyncCoordinator
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.plants.data.PlantRemoteDataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class PlantOverview(
    val plant: PlantResponse,
    val device: DeviceResponse?,
    val latest: PlantLatestResponse?,
    val activeAlerts: List<AlertEventResponse>,
)

data class PlantDetails(
    val overview: PlantOverview,
    val history: PlantHistoryResponse?,
)

interface PlantRepository {
    fun observePlants(): Flow<List<PlantOverview>>
    fun observePlant(plantId: String, historyKey: HistoryCacheKey): Flow<PlantDetails?>
    fun lastDayHistoryKey(plantId: String): HistoryCacheKey
    suspend fun refreshPlants(): RefreshResult
    suspend fun refreshPlant(plantId: String, historyKey: HistoryCacheKey): RefreshResult
    suspend fun createPlant(name: String, species: String?, imageUrl: String?): PlantResponse
    suspend fun updatePlant(plantId: String, name: String, species: String?, imageUrl: String?): PlantResponse
}

class OfflineFirstPlantRepository(
    private val remote: PlantRemoteDataSource,
    private val cache: OfflineCache,
    private val userSessionStore: UserSessionStore,
    private val syncCoordinator: SyncCoordinator,
    private val now: () -> Instant = { Clock.System.now() },
) : PlantRepository {
    override fun observePlants(): Flow<List<PlantOverview>> {
        val accountId = accountId()
        return combine(
            cache.observePlants(accountId),
            cache.observeDevices(accountId),
            cache.observeLatestStates(accountId),
            cache.observeAllAlertEvents(accountId),
        ) { plants, devices, latestStates, alerts ->
            plants.map { plant ->
                val plantDevices = devices.filter { it.plantId == plant.id }
                val latest = latestStates.firstOrNull { it.plantId == plant.id }
                PlantOverview(
                    plant = plant,
                    device = latest?.let { state -> plantDevices.firstOrNull { it.id == state.deviceId } }
                        ?: plantDevices.firstOrNull(),
                    latest = latest,
                    activeAlerts = alerts.asSequence()
                        .filter { (plantId, alert) -> plantId == plant.id && alert.status == AlertStatus.ACTIVE }
                        .map { it.second }
                        .toList(),
                )
            }
        }
    }

    override fun observePlant(plantId: String, historyKey: HistoryCacheKey): Flow<PlantDetails?> = combine(
        observePlants().map { plants -> plants.firstOrNull { it.plant.id == plantId } },
        cache.observeHistory(historyKey),
    ) { overview, history -> overview?.let { PlantDetails(it, history) } }

    override fun lastDayHistoryKey(plantId: String): HistoryCacheKey {
        val end = now()
        return HistoryCacheKey(
            accountId = accountId(),
            plantId = plantId,
            interval = HistoryInterval.FIVE_MINUTES,
            rangeStart = (end - 24.hours).toString(),
            rangeEnd = end.toString(),
        )
    }

    override suspend fun refreshPlants(): RefreshResult {
        val accountId = accountId()
        val plants = try {
            remote.listPlants()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return RefreshResult.PreservedCache(failure)
        }
        try {
            cache.replacePlants(accountId, plants)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return RefreshResult.PreservedCache(failure)
        }
        var firstFailure: Throwable? = null
        try {
            cache.replaceDevices(accountId, remote.listDevices())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        plants.forEach { plant ->
            refreshLatest(accountId, plant.id) { if (firstFailure == null) firstFailure = it }
            try {
                cache.replaceAlertEvents(accountId, plant.id, remote.alerts(plant.id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        if (firstFailure == null) {
            try {
                cache.markSynced(accountId, "plants", now().toString())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                firstFailure = failure
            }
        }
        return firstFailure?.let(RefreshResult::PreservedCache) ?: RefreshResult.Updated
    }

    override suspend fun refreshPlant(plantId: String, historyKey: HistoryCacheKey): RefreshResult {
        val accountId = accountId()
        val plant = try {
            remote.getPlant(plantId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return RefreshResult.PreservedCache(failure)
        }
        try {
            cache.upsertPlant(accountId, plant)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return RefreshResult.PreservedCache(failure)
        }
        var firstFailure: Throwable? = null
        try {
            cache.replaceDevices(accountId, remote.listDevices())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        refreshLatest(accountId, plantId) { if (firstFailure == null) firstFailure = it }
        try {
            cache.replaceAlertEvents(accountId, plantId, remote.alerts(plantId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
        try {
            val history = remote.history(
                plantId,
                historyKey.rangeStart,
                historyKey.rangeEnd,
                historyKey.interval,
            )
            cache.replaceHistory(historyKey, history)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
        if (firstFailure == null) {
            try {
                cache.markSynced(accountId, "plant:$plantId", now().toString())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                firstFailure = failure
            }
        }
        return firstFailure?.let(RefreshResult::PreservedCache) ?: RefreshResult.Updated
    }

    override suspend fun createPlant(
        name: String,
        species: String?,
        imageUrl: String?,
    ): PlantResponse = syncCoordinator.serverFirstMutation(
        mutateServer = { remote.createPlant(CreatePlantRequest(name, species, imageUrl)) },
        updateCache = { cache.upsertPlant(accountId(), it) },
    )

    override suspend fun updatePlant(
        plantId: String,
        name: String,
        species: String?,
        imageUrl: String?,
    ): PlantResponse = syncCoordinator.serverFirstMutation(
        mutateServer = { remote.updatePlant(plantId, UpdatePlantRequest(name, species, imageUrl)) },
        updateCache = { cache.upsertPlant(accountId(), it) },
    )

    private suspend fun refreshLatest(accountId: String, plantId: String, onFailure: (Throwable) -> Unit) {
        try {
            cache.replaceLatestState(accountId, remote.latest(plantId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ApiException.Backend) {
            if (failure.errorCode == "NOT_FOUND") {
                try {
                    cache.removeLatestState(accountId, plantId)
                } catch (cacheFailure: Throwable) {
                    if (cacheFailure is CancellationException) throw cacheFailure
                    onFailure(cacheFailure)
                }
            } else {
                onFailure(failure)
            }
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }

    private fun accountId(): String = userSessionStore.session.value?.userId
        ?: error("An internal user session is required for plant data")
}
