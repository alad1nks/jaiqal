package com.alad1nks.jaiqal.feature.devices.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.devices.domain.CalibrationSample
import com.alad1nks.jaiqal.feature.devices.domain.ClaimResultUncertain
import com.alad1nks.jaiqal.feature.devices.domain.DeviceOffline
import com.alad1nks.jaiqal.feature.devices.domain.DeviceOverview
import com.alad1nks.jaiqal.feature.devices.domain.DeviceRepository
import com.alad1nks.jaiqal.feature.devices.domain.MeasurementUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeviceUiError {
    INVALID_CODE,
    CODE_UNAVAILABLE,
    RESULT_UNCERTAIN,
    OFFLINE,
    DEVICE_OFFLINE,
    MEASUREMENT_UNAVAILABLE,
    INVALID_CALIBRATION,
    NO_PLANT,
    NOT_FOUND,
    SERVER,
}

data class ClaimDeviceUiState(
    val plants: List<PlantResponse> = emptyList(),
    val selectedPlantId: String? = null,
    val claimCode: String = "",
    val isLoading: Boolean = false,
    val resultWasUncertain: Boolean = false,
    val claimedDevice: DeviceResponse? = null,
    val error: DeviceUiError? = null,
)

class ClaimDeviceViewModel(
    initialPlantId: String?,
    private val repository: DeviceRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ClaimDeviceUiState(selectedPlantId = initialPlantId))
    val state: StateFlow<ClaimDeviceUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observePlants().collect { plants ->
                mutableState.update { current ->
                    current.copy(
                        plants = plants,
                        selectedPlantId = current.selectedPlantId
                            ?.takeIf { id -> plants.any { it.id == id } }
                            ?: plants.singleOrNull()?.id,
                    )
                }
            }
        }
        refresh()
    }

    fun setClaimCode(value: String) = mutableState.update {
        it.copy(claimCode = value.trim().take(MAX_CLAIM_CODE_LENGTH), error = null)
    }

    fun selectPlant(plantId: String) = mutableState.update {
        it.copy(selectedPlantId = plantId, resultWasUncertain = false, error = null)
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                repository.refreshDevices()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Claim remains usable with the cached plant list.
            }
        }
    }

    fun submit() {
        val current = mutableState.value
        val plantId = current.selectedPlantId ?: return mutableState.update {
            it.copy(error = DeviceUiError.NO_PLANT)
        }
        if (current.claimCode.isBlank()) {
            mutableState.update { it.copy(error = DeviceUiError.INVALID_CODE) }
            return
        }
        if (current.isLoading) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val reconciled = if (current.resultWasUncertain) repository.reconcileClaim(plantId) else null
                val device = reconciled ?: repository.claimDevice(current.claimCode, plantId)
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        resultWasUncertain = false,
                        claimCode = "",
                        claimedDevice = device,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ClaimResultUncertain) {
                mutableState.update {
                    it.copy(isLoading = false, resultWasUncertain = true, error = DeviceUiError.RESULT_UNCERTAIN)
                }
            } catch (failure: Throwable) {
                val error = if (failure is ApiException.Backend && failure.errorCode == "NOT_FOUND") {
                    DeviceUiError.CODE_UNAVAILABLE
                } else {
                    failure.toDeviceError()
                }
                mutableState.update { it.copy(isLoading = false, error = error) }
            }
        }
    }

    private companion object {
        const val MAX_CLAIM_CODE_LENGTH = 128
    }
}

data class DeviceDetailsUiState(
    val overview: DeviceOverview? = null,
    val isLoading: Boolean = true,
    val error: DeviceUiError? = null,
)

class DeviceDetailsViewModel(
    private val deviceId: String,
    private val repository: DeviceRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DeviceDetailsUiState())
    val state: StateFlow<DeviceDetailsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeDevices().collect { devices ->
                mutableState.update { it.copy(overview = devices.firstOrNull { item -> item.device.id == deviceId }) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.refreshDevices()
                mutableState.update { it.copy(isLoading = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update { it.copy(isLoading = false, error = failure.toDeviceError()) }
            }
        }
    }
}

enum class CalibrationStep { INTRODUCTION, DRY_SAMPLE, WET_SAMPLE, REVIEW, CONFIRMATION }

data class CalibrationUiState(
    val step: CalibrationStep = CalibrationStep.INTRODUCTION,
    val drySample: CalibrationSample? = null,
    val wetSample: CalibrationSample? = null,
    val isLoading: Boolean = false,
    val saved: Boolean = false,
    val error: DeviceUiError? = null,
) {
    val isReversed: Boolean get() = drySample != null && wetSample != null && wetSample.raw > drySample.raw
}

class CalibrationViewModel(
    private val deviceId: String,
    private val repository: DeviceRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = mutableState.asStateFlow()

    fun next() {
        mutableState.update { current ->
            when (current.step) {
                CalibrationStep.INTRODUCTION -> current.copy(step = CalibrationStep.DRY_SAMPLE, error = null)
                CalibrationStep.REVIEW -> current.copy(step = CalibrationStep.CONFIRMATION, error = null)
                else -> current
            }
        }
    }

    fun back() = mutableState.update { current ->
        current.copy(
            step = when (current.step) {
                CalibrationStep.INTRODUCTION -> CalibrationStep.INTRODUCTION
                CalibrationStep.DRY_SAMPLE -> CalibrationStep.INTRODUCTION
                CalibrationStep.WET_SAMPLE -> CalibrationStep.DRY_SAMPLE
                CalibrationStep.REVIEW -> CalibrationStep.WET_SAMPLE
                CalibrationStep.CONFIRMATION -> CalibrationStep.REVIEW
            },
            error = null,
        )
    }

    fun capture() {
        val current = mutableState.value
        if (current.isLoading || current.step !in setOf(CalibrationStep.DRY_SAMPLE, CalibrationStep.WET_SAMPLE)) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val sample = repository.captureSoilSample(deviceId)
                mutableState.update { state ->
                    when (state.step) {
                        CalibrationStep.DRY_SAMPLE -> state.copy(
                            step = CalibrationStep.WET_SAMPLE,
                            drySample = sample,
                            wetSample = null,
                            isLoading = false,
                        )
                        CalibrationStep.WET_SAMPLE -> if (sample.raw == state.drySample?.raw) {
                            state.copy(isLoading = false, error = DeviceUiError.INVALID_CALIBRATION)
                        } else {
                            state.copy(step = CalibrationStep.REVIEW, wetSample = sample, isLoading = false)
                        }
                        else -> state.copy(isLoading = false)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update { it.copy(isLoading = false, error = failure.toDeviceError()) }
            }
        }
    }

    fun confirm() {
        val current = mutableState.value
        val dry = current.drySample?.raw ?: return
        val wet = current.wetSample?.raw ?: return
        if (current.step != CalibrationStep.CONFIRMATION || current.isLoading || dry == wet) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.updateCalibration(deviceId, dry, wet)
                mutableState.update { it.copy(isLoading = false, saved = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update { it.copy(isLoading = false, error = failure.toDeviceError()) }
            }
        }
    }
}

private fun Throwable.toDeviceError(): DeviceUiError = when (this) {
    is ClaimResultUncertain -> DeviceUiError.RESULT_UNCERTAIN
    is DeviceOffline -> DeviceUiError.DEVICE_OFFLINE
    is MeasurementUnavailable -> DeviceUiError.MEASUREMENT_UNAVAILABLE
    is ApiException.Connectivity, is ApiException.Timeout -> DeviceUiError.OFFLINE
    is IllegalArgumentException -> DeviceUiError.INVALID_CALIBRATION
    is ApiException.Backend -> when (errorCode) {
        "INVALID_CLAIM_CODE" -> DeviceUiError.INVALID_CODE
        "INVALID_CALIBRATION" -> DeviceUiError.INVALID_CALIBRATION
        "NOT_FOUND" -> DeviceUiError.NOT_FOUND
        else -> DeviceUiError.SERVER
    }
    else -> DeviceUiError.SERVER
}
