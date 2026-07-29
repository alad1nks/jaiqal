package com.alad1nks.jaiqal.feature.plants.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.core.cache.OfflineMutationException
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.plants.domain.PlantDetails
import com.alad1nks.jaiqal.feature.plants.domain.PlantOverview
import com.alad1nks.jaiqal.feature.plants.domain.PlantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCached: Boolean = false,
    val error: PlantUiError? = null,
)

class PlantDetailsViewModel(
    plantId: String,
    private val repository: PlantRepository,
) : ViewModel() {
    private val historyKey = repository.lastDayHistoryKey(plantId)
    private val mutableState = MutableStateFlow(PlantDetailsUiState())
    val state: StateFlow<PlantDetailsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observePlant(plantId, historyKey).collect { details ->
                mutableState.update { it.copy(details = details) }
            }
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true) }
            val result = try {
                repository.refreshPlant(plantId, historyKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                RefreshResult.PreservedCache(failure)
            }
            when (result) {
                RefreshResult.Updated -> mutableState.update {
                    it.copy(isInitialLoading = false, isRefreshing = false, isCached = false, error = null)
                }
                is RefreshResult.PreservedCache -> mutableState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isCached = it.details != null,
                        error = result.cause.toPlantError(),
                    )
                }
            }
        }
    }
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
