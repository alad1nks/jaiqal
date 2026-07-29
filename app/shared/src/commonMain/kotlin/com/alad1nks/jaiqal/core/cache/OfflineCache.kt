package com.alad1nks.jaiqal.core.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class HistoryCacheKey(
    val accountId: String,
    val plantId: String,
    val interval: HistoryInterval,
    val rangeStart: String,
    val rangeEnd: String,
)

data class CacheMetadata(
    val cacheKey: String,
    val syncedAt: String,
)

interface OfflineCache {
    fun observeUser(accountId: String): Flow<CurrentUserResponse?>
    fun observePlants(accountId: String): Flow<List<PlantResponse>>
    fun observeDevices(accountId: String): Flow<List<DeviceResponse>>
    fun observeLatestStates(accountId: String): Flow<List<PlantLatestResponse>>
    fun observeHistory(key: HistoryCacheKey): Flow<PlantHistoryResponse?>
    fun observeAlertEvents(accountId: String, plantId: String): Flow<List<AlertEventResponse>>
    fun observeAllAlertEvents(accountId: String): Flow<List<Pair<String, AlertEventResponse>>>
    fun observeAlertRules(accountId: String, plantId: String): Flow<List<AlertRuleResponse>>
    fun observeMetadata(accountId: String, cacheKey: String): Flow<CacheMetadata?>

    suspend fun replaceUser(user: CurrentUserResponse)
    suspend fun replacePlants(accountId: String, plants: List<PlantResponse>)
    suspend fun upsertPlant(accountId: String, plant: PlantResponse)
    suspend fun replaceDevices(accountId: String, devices: List<DeviceResponse>)
    suspend fun replaceLatestState(accountId: String, latest: PlantLatestResponse)
    suspend fun removeLatestState(accountId: String, plantId: String)
    suspend fun replaceHistory(key: HistoryCacheKey, history: PlantHistoryResponse)
    suspend fun replaceAlertEvents(accountId: String, plantId: String, alerts: List<AlertEventResponse>)
    suspend fun replaceAlertRules(accountId: String, plantId: String, rules: List<AlertRuleResponse>)
    suspend fun markSynced(accountId: String, cacheKey: String, syncedAt: String)
    suspend fun clearAccount(accountId: String)
}

class SqlDelightOfflineCache(database: JaiqalDatabase) : OfflineCache {
    private val queries = database.cacheMetadataQueries

    override fun observeUser(accountId: String): Flow<CurrentUserResponse?> = queries
        .selectCachedUser(accountId) { id, email, verified -> CurrentUserResponse(id, email, verified != 0L) }
        .asFlow()
        .mapToOneOrNull(Dispatchers.Default)

    override fun observePlants(accountId: String): Flow<List<PlantResponse>> = queries
        .selectPlants(accountId) { _, id, name, species, imageUrl, createdAt ->
            PlantResponse(id, name, species, imageUrl, createdAt)
        }
        .asFlow()
        .mapToList(Dispatchers.Default)

    override fun observeDevices(accountId: String): Flow<List<DeviceResponse>> = queries
        .selectDevices(accountId) { _, id, plantId, name, firmwareVersion, lastSeenAt, dry, wet ->
            DeviceResponse(id, plantId, name, firmwareVersion, lastSeenAt, dry?.toInt(), wet?.toInt())
        }
        .asFlow()
        .mapToList(Dispatchers.Default)

    override fun observeLatestStates(accountId: String): Flow<List<PlantLatestResponse>> = queries
        .selectLatestStates(accountId) { _, deviceId, plantId, measuredAt, receivedAt, moisture, raw,
            temperature, humidity, light, online, calibrated ->
            PlantLatestResponse(
                plantId = plantId,
                deviceId = deviceId,
                measuredAt = measuredAt,
                receivedAt = receivedAt,
                soilMoisturePercent = moisture,
                soilMoistureRaw = raw?.toInt(),
                airTemperatureCelsius = temperature,
                airHumidityPercent = humidity,
                lightRaw = light?.toInt(),
                online = online != 0L,
                calibrated = calibrated != 0L,
            )
        }
        .asFlow()
        .mapToList(Dispatchers.Default)

    override fun observeHistory(key: HistoryCacheKey): Flow<PlantHistoryResponse?> = queries
        .selectHistory(key.accountId, key.plantId, key.interval.name, key.rangeStart, key.rangeEnd) {
                _, _, _, _, _, measuredAt, moisture, raw, temperature, humidity, light ->
            PlantHistoryPoint(measuredAt, moisture, raw, temperature, humidity, light)
        }
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { points ->
            if (points.isEmpty()) null else PlantHistoryResponse(key.plantId, key.interval, points)
        }

    override fun observeAlertEvents(accountId: String, plantId: String): Flow<List<AlertEventResponse>> =
        queries.selectAlertEvents(accountId, plantId) { _, _, id, type, status, triggered, recovered,
            acknowledged, observed ->
            AlertEventResponse(
                id = id,
                type = AlertType.valueOf(type),
                status = AlertStatus.valueOf(status),
                triggeredAt = triggered,
                recoveredAt = recovered,
                acknowledgedAt = acknowledged,
                lastObservedAt = observed,
            )
        }.asFlow().mapToList(Dispatchers.Default)

    override fun observeAllAlertEvents(accountId: String): Flow<List<Pair<String, AlertEventResponse>>> =
        queries.selectAllAlertEvents(accountId) { _, plantId, id, type, status, triggered, recovered,
            acknowledged, observed ->
            plantId to AlertEventResponse(
                id = id,
                type = AlertType.valueOf(type),
                status = AlertStatus.valueOf(status),
                triggeredAt = triggered,
                recoveredAt = recovered,
                acknowledgedAt = acknowledged,
                lastObservedAt = observed,
            )
        }.asFlow().mapToList(Dispatchers.Default)

    override fun observeAlertRules(accountId: String, plantId: String): Flow<List<AlertRuleResponse>> =
        queries.selectAlertRules(accountId, plantId) { _, _, id, type, threshold, required, recovery, enabled ->
            AlertRuleResponse(
                id = id,
                type = AlertType.valueOf(type),
                threshold = threshold,
                requiredDurationSeconds = required,
                recoveryDurationSeconds = recovery,
                enabled = enabled != 0L,
            )
        }.asFlow().mapToList(Dispatchers.Default)

    override fun observeMetadata(accountId: String, cacheKey: String): Flow<CacheMetadata?> = queries
        .selectCacheMetadata(accountId, cacheKey) { _, key, syncedAt -> CacheMetadata(key, syncedAt) }
        .asFlow()
        .mapToOneOrNull(Dispatchers.Default)

    override suspend fun replaceUser(user: CurrentUserResponse) {
        queries.upsertCachedUser(user.id, user.email, user.emailVerified.asLong())
    }

    override suspend fun replacePlants(accountId: String, plants: List<PlantResponse>) {
        queries.transaction {
            queries.deletePlants(accountId)
            plants.forEach { queries.replacePlant(accountId, it.id, it.name, it.species, it.imageUrl, it.createdAt) }
        }
    }

    override suspend fun upsertPlant(accountId: String, plant: PlantResponse) {
        queries.replacePlant(accountId, plant.id, plant.name, plant.species, plant.imageUrl, plant.createdAt)
    }

    override suspend fun replaceDevices(accountId: String, devices: List<DeviceResponse>) {
        queries.transaction {
            queries.deleteDevices(accountId)
            devices.forEach {
                queries.replaceDevice(
                    accountId, it.id, it.plantId, it.name, it.firmwareVersion, it.lastSeenAt,
                    it.soilDryRaw?.toLong(), it.soilWetRaw?.toLong(),
                )
            }
        }
    }

    override suspend fun replaceLatestState(accountId: String, latest: PlantLatestResponse) {
        queries.replaceLatestState(
            accountId, latest.deviceId, latest.plantId, latest.measuredAt, latest.receivedAt,
            latest.soilMoisturePercent, latest.soilMoistureRaw?.toLong(), latest.airTemperatureCelsius,
            latest.airHumidityPercent, latest.lightRaw?.toLong(), latest.online.asLong(), latest.calibrated.asLong(),
        )
    }

    override suspend fun removeLatestState(accountId: String, plantId: String) {
        queries.deleteLatestStateForPlant(accountId, plantId)
    }

    override suspend fun replaceHistory(key: HistoryCacheKey, history: PlantHistoryResponse) {
        require(history.plantId == key.plantId && history.interval == key.interval)
        queries.transaction {
            queries.deleteHistoryRange(key.accountId, key.plantId, key.interval.name, key.rangeStart, key.rangeEnd)
            history.points.forEach {
                queries.insertHistoryPoint(
                    key.accountId, key.plantId, key.interval.name, key.rangeStart, key.rangeEnd,
                    it.measuredAt, it.soilMoisturePercent, it.soilMoistureRaw,
                    it.airTemperatureCelsius, it.airHumidityPercent, it.lightRaw,
                )
            }
        }
    }

    override suspend fun replaceAlertEvents(
        accountId: String,
        plantId: String,
        alerts: List<AlertEventResponse>,
    ) {
        queries.transaction {
            queries.deleteAlertEvents(accountId, plantId)
            alerts.forEach {
                queries.insertAlertEvent(
                    accountId, plantId, it.id, it.type.name, it.status.name, it.triggeredAt,
                    it.recoveredAt, it.acknowledgedAt, it.lastObservedAt,
                )
            }
        }
    }

    override suspend fun replaceAlertRules(
        accountId: String,
        plantId: String,
        rules: List<AlertRuleResponse>,
    ) {
        queries.transaction {
            queries.deleteAlertRules(accountId, plantId)
            rules.forEach {
                queries.insertAlertRule(
                    accountId, plantId, it.id, it.type.name, it.threshold,
                    it.requiredDurationSeconds, it.recoveryDurationSeconds, it.enabled.asLong(),
                )
            }
        }
    }

    override suspend fun markSynced(accountId: String, cacheKey: String, syncedAt: String) {
        queries.upsertCacheMetadata(accountId, cacheKey, syncedAt)
    }

    override suspend fun clearAccount(accountId: String) {
        queries.transaction {
            queries.deleteCacheForAccount(accountId)
            queries.deleteCachedUserForAccount(accountId)
            queries.deletePlants(accountId)
            queries.deleteDevicesForAccount(accountId)
            queries.deleteLatestStatesForAccount(accountId)
            queries.deleteHistoryForAccount(accountId)
            queries.deleteAlertEventsForAccount(accountId)
            queries.deleteAlertRulesForAccount(accountId)
        }
    }

    private fun Boolean.asLong(): Long = if (this) 1L else 0L
}

object NoOpOfflineCache : OfflineCache {
    override fun observeUser(accountId: String) = flowOf<CurrentUserResponse?>(null)
    override fun observePlants(accountId: String) = flowOf(emptyList<PlantResponse>())
    override fun observeDevices(accountId: String) = flowOf(emptyList<DeviceResponse>())
    override fun observeLatestStates(accountId: String) = flowOf(emptyList<PlantLatestResponse>())
    override fun observeHistory(key: HistoryCacheKey) = flowOf<PlantHistoryResponse?>(null)
    override fun observeAlertEvents(accountId: String, plantId: String) = flowOf(emptyList<AlertEventResponse>())
    override fun observeAllAlertEvents(accountId: String) = flowOf(emptyList<Pair<String, AlertEventResponse>>())
    override fun observeAlertRules(accountId: String, plantId: String) = flowOf(emptyList<AlertRuleResponse>())
    override fun observeMetadata(accountId: String, cacheKey: String) = flowOf<CacheMetadata?>(null)
    override suspend fun replaceUser(user: CurrentUserResponse) = Unit
    override suspend fun replacePlants(accountId: String, plants: List<PlantResponse>) = Unit
    override suspend fun upsertPlant(accountId: String, plant: PlantResponse) = Unit
    override suspend fun replaceDevices(accountId: String, devices: List<DeviceResponse>) = Unit
    override suspend fun replaceLatestState(accountId: String, latest: PlantLatestResponse) = Unit
    override suspend fun removeLatestState(accountId: String, plantId: String) = Unit
    override suspend fun replaceHistory(key: HistoryCacheKey, history: PlantHistoryResponse) = Unit
    override suspend fun replaceAlertEvents(accountId: String, plantId: String, alerts: List<AlertEventResponse>) = Unit
    override suspend fun replaceAlertRules(accountId: String, plantId: String, rules: List<AlertRuleResponse>) = Unit
    override suspend fun markSynced(accountId: String, cacheKey: String, syncedAt: String) = Unit
    override suspend fun clearAccount(accountId: String) = Unit
}
