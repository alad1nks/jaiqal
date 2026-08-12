package com.alad1nks.jaiqal.feature.alerts.domain

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.PutAlertRuleRequest
import com.alad1nks.jaiqal.api.contract.PutAlertRulesRequest
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.feature.alerts.data.AlertLocalDataSource
import com.alad1nks.jaiqal.feature.alerts.data.AlertRemoteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AlertRepositoryTest {
    @Test
    fun refreshCachesEventsForEveryAuthoritativePlant() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        val repository = OfflineFirstAlertRepository(remote, local)

        assertEquals(RefreshResult.Updated, repository.refreshAlerts())

        assertEquals(listOf("plant-a", "plant-b"), local.plants.value.map { it.id })
        assertEquals(setOf("plant-a", "plant-b"), local.events.keys)
        assertEquals(2, repository.observeAlerts().first().size)
    }

    @Test
    fun rejectedRuleSaveDoesNotReplaceServerBackedCache() = runTest {
        val local = FakeLocal().apply { rules.value = listOf(rule(30.0)) }
        val remote = FakeRemote().apply { putFailure = IllegalStateException("rejected") }
        val repository = OfflineFirstAlertRepository(remote, local)

        assertFailsWith<IllegalStateException> {
            repository.saveRules("plant-a", listOf(PutAlertRuleRequest(AlertType.LOW_SOIL_MOISTURE, 10.0)))
        }

        assertEquals(30.0, local.rules.value.single().threshold)
    }

    @Test
    fun acknowledgeUpdatesCacheOnlyAfterServerSuccess() = runTest {
        val local = FakeLocal()
        local.seedEvents("plant-a", listOf(event("plant-a")))
        val repository = OfflineFirstAlertRepository(FakeRemote(), local)

        repository.acknowledge("plant-a", "alert-plant-a")

        assertEquals("2026-08-12T01:00:00Z", local.events.getValue("plant-a").value.single().acknowledgedAt)
    }

    @Test
    fun failedRefreshPreservesCachedEvents() = runTest {
        val local = FakeLocal().apply {
            plants.value = listOf(plant("plant-a"))
            seedEvents("plant-a", listOf(event("plant-a")))
        }
        val repository = OfflineFirstAlertRepository(
            FakeRemote().apply { plantFailure = IllegalStateException("offline") },
            local,
        )

        assertIs<RefreshResult.PreservedCache>(repository.refreshAlerts())
        assertEquals(1, local.events.getValue("plant-a").value.size)
    }

    private class FakeRemote : AlertRemoteDataSource {
        var putFailure: Throwable? = null
        var plantFailure: Throwable? = null
        override suspend fun plants(): List<PlantResponse> {
            plantFailure?.let { throw it }
            return listOf(plant("plant-a"), plant("plant-b"))
        }
        override suspend fun events(plantId: String) = listOf(event(plantId))
        override suspend fun rules(plantId: String) = listOf(rule(25.0))
        override suspend fun putRules(plantId: String, request: PutAlertRulesRequest): List<AlertRuleResponse> {
            putFailure?.let { throw it }
            return request.rules.mapIndexed { index, item ->
                AlertRuleResponse(
                    "rule-$index", item.type, item.threshold, item.requiredDurationSeconds,
                    item.recoveryDurationSeconds, item.enabled,
                )
            }
        }
        override suspend fun acknowledge(plantId: String, alertId: String) =
            event(plantId).copy(acknowledgedAt = "2026-08-12T01:00:00Z")
    }

    private class FakeLocal : AlertLocalDataSource {
        val plants = MutableStateFlow(emptyList<PlantResponse>())
        val events = mutableMapOf<String, MutableStateFlow<List<AlertEventResponse>>>()
        val allEvents = MutableStateFlow(emptyList<Pair<String, AlertEventResponse>>())
        val rules = MutableStateFlow(emptyList<AlertRuleResponse>())
        override fun observePlants(): Flow<List<PlantResponse>> = plants
        override fun observeAllEvents(): Flow<List<Pair<String, AlertEventResponse>>> = allEvents
        override fun observeRules(plantId: String): Flow<List<AlertRuleResponse>> = rules
        override suspend fun replacePlants(plants: List<PlantResponse>) { this.plants.value = plants }
        override suspend fun replaceEvents(plantId: String, events: List<AlertEventResponse>) {
            this.events.getOrPut(plantId) { MutableStateFlow(emptyList()) }.value = events
            updateAllEvents()
        }
        override suspend fun replaceRules(plantId: String, rules: List<AlertRuleResponse>) {
            this.rules.value = rules
        }
        override suspend fun updateEvent(plantId: String, event: AlertEventResponse) {
            val flow = events.getOrPut(plantId) { MutableStateFlow(emptyList()) }
            flow.value = flow.value.map { if (it.id == event.id) event else it }
            updateAllEvents()
        }
        fun seedEvents(plantId: String, events: List<AlertEventResponse>) {
            this.events[plantId] = MutableStateFlow(events)
            updateAllEvents()
        }
        private fun updateAllEvents() {
            allEvents.value = events.flatMap { (plantId, flow) -> flow.value.map { plantId to it } }
        }
    }

    companion object {
        private fun plant(id: String) = PlantResponse(id, "Plant $id", createdAt = "2026-08-12T00:00:00Z")
        private fun event(plantId: String) = AlertEventResponse(
            "alert-$plantId", AlertType.DEVICE_OFFLINE, AlertStatus.ACTIVE,
            "2026-08-12T00:00:00Z", lastObservedAt = "2026-08-12T00:00:00Z",
        )
        private fun rule(threshold: Double) = AlertRuleResponse(
            "rule-a", AlertType.LOW_SOIL_MOISTURE, threshold, 60, 30, true,
        )
    }
}
