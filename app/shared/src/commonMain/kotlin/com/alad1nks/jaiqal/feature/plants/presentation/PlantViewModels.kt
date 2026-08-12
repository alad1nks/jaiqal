package com.alad1nks.jaiqal.feature.plants.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.core.cache.OfflineMutationException
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.plants.domain.PlantDetails
import com.alad1nks.jaiqal.feature.plants.domain.HistoryRange
import com.alad1nks.jaiqal.feature.plants.domain.PlantOverview
import com.alad1nks.jaiqal.feature.plants.domain.PlantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class PlantUiError {
    OFFLINE,
    INVALID_NAME,
    INVALID_SPECIES,
    INVALID_IMAGE_URL,
    NOT_FOUND,
    SERVER,
}

data class PlantsUiState(
    val plants: List<PlantOverview> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCached: Boolean = false,
    val error: PlantUiError? = null,
)

class PlantsViewModel(private val repository: PlantRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PlantsUiState())
    val state: StateFlow<PlantsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observePlants().collect { plants ->
                mutableState.update { it.copy(plants = plants) }
            }
        }
        refresh()
    }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true, error = null) }
            val result = try {
                repository.refreshPlants()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                RefreshResult.PreservedCache(failure)
            }
            mutableState.update { current ->
                when (result) {
                    RefreshResult.Updated -> current.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isCached = false,
                        error = null,
                    )
                    is RefreshResult.PreservedCache -> current.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isCached = current.plants.isNotEmpty(),
                        error = result.cause.toPlantError(),
                    )
                }
            }
        }
    }
}

data class PlantDetailsUiState(
    val details: PlantDetails? = null,
    val selectedRange: HistoryRange = HistoryRange.LAST_24_HOURS,
    val isHistoryLoading: Boolean = true,
    val historyError: PlantUiError? = null,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCached: Boolean = false,
    val error: PlantUiError? = null,
)

class PlantDetailsViewModel(
    private val plantId: String,
    private val repository: PlantRepository,
) : ViewModel() {
    private val selectedHistoryKey = MutableStateFlow(repository.lastDayHistoryKey(plantId))
    private val mutableState = MutableStateFlow(PlantDetailsUiState())
    val state: StateFlow<PlantDetailsUiState> = mutableState.asStateFlow()
    private var realtimeJob: Job? = null
    private var historyRefreshJob: Job? = null
    private var enteredForeground = false

    init {
        viewModelScope.launch {
            selectedHistoryKey.collectLatest { key ->
                repository.observePlant(plantId, key).collect { details ->
                    mutableState.update { it.copy(details = details) }
                }
            }
        }
        refreshPlant()
    }

    fun selectHistoryRange(range: HistoryRange) {
        if (range == mutableState.value.selectedRange) return
        val key = repository.historyKey(plantId, range)
        selectedHistoryKey.value = key
        mutableState.update {
            it.copy(selectedRange = range, isHistoryLoading = true, historyError = null)
        }
        historyRefreshJob?.cancel()
        historyRefreshJob = viewModelScope.launch {
            applyHistoryResult(key, repository.refreshHistory(key))
        }
    }

    fun retryHistory() {
        if (mutableState.value.isHistoryLoading) return
        mutableState.update { it.copy(isHistoryLoading = true, historyError = null) }
        val key = selectedHistoryKey.value
        historyRefreshJob?.cancel()
        historyRefreshJob = viewModelScope.launch { applyHistoryResult(key, repository.refreshHistory(key)) }
    }

    fun onForeground() {
        if (realtimeJob?.isActive == true) return
        if (enteredForeground) refreshPlant() else enteredForeground = true
        realtimeJob = viewModelScope.launch {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                try {
                    repository.realtimeMeasurements(plantId).collect { update ->
                        if (update.plantId == plantId) {
                            attempt = 0
                            repository.refreshRealtimeState(plantId, selectedHistoryKey.value)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // The next iteration reconnects with a bounded exponential delay.
                }
                delay(realtimeRetryDelayMillis(attempt++).milliseconds)
            }
        }
    }

    fun onBackground() {
        realtimeJob?.cancel()
        realtimeJob = null
    }

    private fun refreshPlant() {
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true) }
            val result = try {
                repository.refreshPlant(plantId, selectedHistoryKey.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                RefreshResult.PreservedCache(failure)
            }
            when (result) {
                RefreshResult.Updated -> mutableState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isHistoryLoading = false,
                        isCached = false,
                        error = null,
                        historyError = null,
                    )
                }
                is RefreshResult.PreservedCache -> mutableState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isHistoryLoading = false,
                        isCached = it.details != null,
                        error = result.cause.toPlantError(),
                        historyError = if (it.details?.history == null) result.cause.toPlantError() else null,
                    )
                }
            }
        }
    }

    private fun applyHistoryResult(key: com.alad1nks.jaiqal.core.cache.HistoryCacheKey, result: RefreshResult) {
        if (key != selectedHistoryKey.value) return
        mutableState.update { current ->
            when (result) {
                RefreshResult.Updated -> current.copy(isHistoryLoading = false, historyError = null)
                is RefreshResult.PreservedCache -> current.copy(
                    isHistoryLoading = false,
                    isCached = current.details?.history != null,
                    historyError = result.cause.toPlantError(),
                )
            }
        }
    }
}

internal fun realtimeRetryDelayMillis(attempt: Int, jitter: Double = Random.nextDouble()): Long {
    val exponential = (1_000.0 * 2.0.pow(attempt.coerceIn(0, 5))).toLong().coerceAtMost(24_000L)
    return exponential + (exponential * 0.25 * jitter.coerceIn(0.0, 1.0)).toLong()
}

data class PlantFormUiState(
    val name: String = "",
    val species: String = "",
    val imageUrl: String = "",
    val isLoading: Boolean = false,
    val error: PlantUiError? = null,
    val savedPlantId: String? = null,
)

abstract class PlantFormViewModel(protected val repository: PlantRepository) : ViewModel() {
    protected val mutableState = MutableStateFlow(PlantFormUiState())
    val state: StateFlow<PlantFormUiState> = mutableState.asStateFlow()

    fun setName(value: String) = mutableState.update { it.copy(name = value, error = null) }
    fun setSpecies(value: String) = mutableState.update { it.copy(species = value, error = null) }
    fun setImageUrl(value: String) = mutableState.update { it.copy(imageUrl = value, error = null) }

    fun save() {
        if (mutableState.value.isLoading) return
        val current = mutableState.value
        val name = current.name.trim()
        val species = current.species.trim().takeIf(String::isNotEmpty)
        val imageUrl = current.imageUrl.trim().takeIf(String::isNotEmpty)
        val validation = when {
            name.isEmpty() || name.length > MAX_NAME_LENGTH -> PlantUiError.INVALID_NAME
            species != null && species.length > MAX_SPECIES_LENGTH -> PlantUiError.INVALID_SPECIES
            imageUrl != null && imageUrl.length > MAX_IMAGE_URL_LENGTH -> PlantUiError.INVALID_IMAGE_URL
            else -> null
        }
        if (validation != null) {
            mutableState.update { it.copy(error = validation) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val saved = submit(name, species, imageUrl)
                mutableState.update { it.copy(isLoading = false, savedPlantId = saved) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update { it.copy(isLoading = false, error = failure.toPlantError()) }
            }
        }
    }

    protected abstract suspend fun submit(name: String, species: String?, imageUrl: String?): String

    protected companion object {
        const val MAX_NAME_LENGTH = 255
        const val MAX_SPECIES_LENGTH = 255
        const val MAX_IMAGE_URL_LENGTH = 2048
    }
}

class CreatePlantViewModel(repository: PlantRepository) : PlantFormViewModel(repository) {
    override suspend fun submit(name: String, species: String?, imageUrl: String?): String =
        repository.createPlant(name, species, imageUrl).id
}

class EditPlantViewModel(
    private val plantId: String,
    repository: PlantRepository,
) : PlantFormViewModel(repository) {
    private var initialized = false

    init {
        viewModelScope.launch {
            repository.observePlants().collect { plants ->
                if (!initialized) {
                    plants.firstOrNull { it.plant.id == plantId }?.let { overview ->
                        initialized = true
                        mutableState.update {
                            it.copy(
                                name = overview.plant.name,
                                species = overview.plant.species.orEmpty(),
                                imageUrl = overview.plant.imageUrl.orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun submit(name: String, species: String?, imageUrl: String?): String =
        repository.updatePlant(plantId, name, species, imageUrl).id
}

private fun Throwable.toPlantError(): PlantUiError = when (this) {
    is OfflineMutationException, is ApiException.Connectivity, is ApiException.Timeout -> PlantUiError.OFFLINE
    is ApiException.Backend -> when (errorCode) {
        "INVALID_NAME" -> PlantUiError.INVALID_NAME
        "NOT_FOUND" -> PlantUiError.NOT_FOUND
        else -> PlantUiError.SERVER
    }
    else -> PlantUiError.SERVER
}
