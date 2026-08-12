package com.alad1nks.jaiqal.feature.alerts.domain

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.PutAlertRuleRequest
import com.alad1nks.jaiqal.api.contract.PutAlertRulesRequest
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.feature.alerts.data.AlertLocalDataSource
import com.alad1nks.jaiqal.feature.alerts.data.AlertRemoteDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class AlertOverview(
    val plantId: String,
    val plantName: String,
    val event: AlertEventResponse,
)

interface AlertRepository {
    fun observeAlerts(): Flow<List<AlertOverview>>
    fun observePlants(): Flow<List<PlantResponse>>
    fun observeRules(plantId: String): Flow<List<AlertRuleResponse>>
    suspend fun refreshAlerts(): RefreshResult
    suspend fun refreshRules(plantId: String): RefreshResult
    suspend fun acknowledge(plantId: String, alertId: String): AlertEventResponse
    suspend fun saveRules(plantId: String, rules: List<PutAlertRuleRequest>): List<AlertRuleResponse>
}

class OfflineFirstAlertRepository(
    private val remote: AlertRemoteDataSource,
    private val local: AlertLocalDataSource,
) : AlertRepository {
    override fun observeAlerts(): Flow<List<AlertOverview>> = combine(
        local.observePlants(),
        local.observeAllEvents(),
    ) { plants, events ->
        val names = plants.associate { it.id to it.name }
        events.mapNotNull { (plantId, event) ->
            names[plantId]?.let { AlertOverview(plantId, it, event) }
        }.sortedByDescending { it.event.triggeredAt }
    }

    override fun observePlants() = local.observePlants()
    override fun observeRules(plantId: String) = local.observeRules(plantId)

    override suspend fun refreshAlerts(): RefreshResult {
        val plants = try {
            remote.plants().also { local.replacePlants(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return RefreshResult.PreservedCache(failure)
        }
        var failure: Throwable? = null
        plants.forEach { plant ->
            try {
                local.replaceEvents(plant.id, remote.events(plant.id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Throwable) {
                failure = failure ?: cause
            }
        }
        return failure?.let(RefreshResult::PreservedCache) ?: RefreshResult.Updated
    }

    override suspend fun refreshRules(plantId: String): RefreshResult = try {
        local.replaceRules(plantId, remote.rules(plantId))
        RefreshResult.Updated
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        RefreshResult.PreservedCache(failure)
    }

    override suspend fun acknowledge(plantId: String, alertId: String): AlertEventResponse {
        val event = remote.acknowledge(plantId, alertId)
        try {
            local.updateEvent(plantId, event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The server mutation succeeded; a later refresh repairs a failed cache write.
        }
        return event
    }

    override suspend fun saveRules(
        plantId: String,
        rules: List<PutAlertRuleRequest>,
    ): List<AlertRuleResponse> {
        val saved = remote.putRules(plantId, PutAlertRulesRequest(rules))
        try {
            local.replaceRules(plantId, saved)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Never turn a successful server mutation into a false failure.
        }
        return saved
    }
}
