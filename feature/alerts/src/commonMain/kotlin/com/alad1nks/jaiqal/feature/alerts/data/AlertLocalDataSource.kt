package com.alad1nks.jaiqal.feature.alerts.data

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.OfflineCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface AlertLocalDataSource {
    fun observePlants(): Flow<List<PlantResponse>>
    fun observeAllEvents(): Flow<List<Pair<String, AlertEventResponse>>>
    fun observeRules(plantId: String): Flow<List<AlertRuleResponse>>
    suspend fun replacePlants(plants: List<PlantResponse>)
    suspend fun replaceEvents(plantId: String, events: List<AlertEventResponse>)
    suspend fun replaceRules(plantId: String, rules: List<AlertRuleResponse>)
    suspend fun updateEvent(plantId: String, event: AlertEventResponse)
}

class CacheAlertLocalDataSource(
    private val cache: OfflineCache,
    private val sessions: UserSessionStore,
) : AlertLocalDataSource {
    override fun observePlants() = cache.observePlants(accountId())
    override fun observeAllEvents() = cache.observeAllAlertEvents(accountId())
    override fun observeRules(plantId: String) = cache.observeAlertRules(accountId(), plantId)

    override suspend fun replacePlants(plants: List<PlantResponse>) {
        cache.replacePlants(accountId(), plants)
    }

    override suspend fun replaceEvents(plantId: String, events: List<AlertEventResponse>) {
        cache.replaceAlertEvents(accountId(), plantId, events)
    }

    override suspend fun replaceRules(plantId: String, rules: List<AlertRuleResponse>) {
        cache.replaceAlertRules(accountId(), plantId, rules)
    }

    override suspend fun updateEvent(plantId: String, event: AlertEventResponse) {
        val current = cache.observeAlertEvents(accountId(), plantId).first()
        cache.replaceAlertEvents(
            accountId(),
            plantId,
            current.map { if (it.id == event.id) event else it },
        )
    }

    private fun accountId(): String = sessions.session.value?.userId
        ?: error("An internal user session is required for alert data")
}
