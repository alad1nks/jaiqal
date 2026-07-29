package com.alad1nks.jaiqal.feature.plants.presentation

import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.cache.HistoryCacheKey
import com.alad1nks.jaiqal.core.cache.OfflineMutationException
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.plants.domain.PlantDetails
import com.alad1nks.jaiqal.feature.plants.domain.PlantOverview
import com.alad1nks.jaiqal.feature.plants.domain.PlantRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PlantViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun listShowsCachedContentAndRecoverableOfflineState() = runTest(dispatcher) {
        val repository = FakePlantRepository().apply {
            plants.value = listOf(PlantOverview(plant("cached", "Cached Aloe"), null, null, emptyList()))
            refreshResult = RefreshResult.PreservedCache(ApiException.Connectivity(IllegalStateException("offline")))
        }

        val viewModel = PlantsViewModel(repository)
        advanceUntilIdle()

        assertEquals("Cached Aloe", viewModel.state.value.plants.single().plant.name)
        assertEquals(PlantUiError.OFFLINE, viewModel.state.value.error)
        assertEquals(true, viewModel.state.value.isCached)
        assertFalse(viewModel.state.value.isInitialLoading)
    }

    @Test
    fun createValidatesRequiredNameBeforeCallingRepository() = runTest(dispatcher) {
        val repository = FakePlantRepository()
        val viewModel = CreatePlantViewModel(repository)

        viewModel.setName("   ")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(PlantUiError.INVALID_NAME, viewModel.state.value.error)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun createTrimsFieldsAndUsesServerIdentifier() = runTest(dispatcher) {
        val repository = FakePlantRepository()
        val viewModel = CreatePlantViewModel(repository)

        viewModel.setName("  Aloe  ")
        viewModel.setSpecies("  Aloe vera ")
        viewModel.setImageUrl("   ")
        viewModel.save()
        advanceUntilIdle()

        assertEquals("server-id", viewModel.state.value.savedPlantId)
        assertEquals(Triple("Aloe", "Aloe vera", null), repository.lastCreate)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun offlineMutationHasClearManagedError() = runTest(dispatcher) {
        val repository = FakePlantRepository().apply {
            createFailure = OfflineMutationException(ApiException.Connectivity(IllegalStateException("offline")))
        }
        val viewModel = CreatePlantViewModel(repository)

        viewModel.setName("Aloe")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(PlantUiError.OFFLINE, viewModel.state.value.error)
        assertNull(viewModel.state.value.savedPlantId)
    }

    private class FakePlantRepository : PlantRepository {
        val plants = MutableStateFlow<List<PlantOverview>>(emptyList())
        var refreshResult: RefreshResult = RefreshResult.Updated
        var createFailure: Throwable? = null
        var createCalls = 0
        var lastCreate: Triple<String, String?, String?>? = null

        override fun observePlants(): Flow<List<PlantOverview>> = plants
        override fun observePlant(plantId: String, historyKey: HistoryCacheKey): Flow<PlantDetails?> =
            plants.map { values -> values.firstOrNull { it.plant.id == plantId }?.let { PlantDetails(it, null) } }
        override fun lastDayHistoryKey(plantId: String) = error("Not used")
        override suspend fun refreshPlants() = refreshResult
        override suspend fun refreshPlant(plantId: String, historyKey: HistoryCacheKey) = refreshResult
        override suspend fun createPlant(name: String, species: String?, imageUrl: String?): PlantResponse {
            createCalls += 1
            createFailure?.let { throw it }
            lastCreate = Triple(name, species, imageUrl)
            return plant("server-id", name)
        }
        override suspend fun updatePlant(
            plantId: String,
            name: String,
            species: String?,
            imageUrl: String?,
        ) = plant(plantId, name)
    }

    private companion object {
        fun plant(id: String, name: String) = PlantResponse(id, name, null, null, "2026-07-29T00:00:00Z")
    }
}
