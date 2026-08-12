package com.alad1nks.jaiqal.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.PutAlertRuleRequest
import com.alad1nks.jaiqal.core.cache.RefreshResult
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.alerts.domain.AlertOverview
import com.alad1nks.jaiqal.feature.alerts.domain.AlertRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AlertUiError { OFFLINE, INVALID_THRESHOLD, INVALID_DURATION, NOT_FOUND, SERVER }

data class AlertsUiState(
    val alerts: List<AlertOverview> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCached: Boolean = false,
    val acknowledgingIds: Set<String> = emptySet(),
    val error: AlertUiError? = null,
)

class AlertsViewModel(private val repository: AlertRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AlertsUiState())
    val state: StateFlow<AlertsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAlerts().collect { alerts -> mutableState.update { it.copy(alerts = alerts) } }
        }
        refresh()
    }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true, error = null) }
            val result = repository.refreshAlerts()
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
                        isCached = current.alerts.isNotEmpty(),
                        error = result.cause.toAlertError(),
                    )
                }
            }
        }
    }

    fun acknowledge(alert: AlertOverview) {
        if (alert.event.acknowledgedAt != null || alert.event.id in mutableState.value.acknowledgingIds) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(acknowledgingIds = it.acknowledgingIds + alert.event.id, error = null)
            }
            try {
                val acknowledged = repository.acknowledge(alert.plantId, alert.event.id)
                mutableState.update { current ->
                    current.copy(
                        alerts = current.alerts.map {
                            if (it.event.id == acknowledged.id) it.copy(event = acknowledged) else it
                        },
                        acknowledgingIds = current.acknowledgingIds - alert.event.id,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        acknowledgingIds = it.acknowledgingIds - alert.event.id,
                        error = failure.toAlertError(),
                    )
                }
            }
        }
    }
}

data class AlertRuleDraft(
    val type: AlertType,
    val configured: Boolean = false,
    val threshold: String = "",
    val requiredDurationSeconds: String = "0",
    val recoveryDurationSeconds: String = "0",
    val enabled: Boolean = true,
)

data class AlertRulesUiState(
    val plants: List<PlantResponse> = emptyList(),
    val selectedPlantId: String? = null,
    val drafts: List<AlertRuleDraft> = AlertType.entries.map(::AlertRuleDraft),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isCached: Boolean = false,
    val isDirty: Boolean = false,
    val saved: Boolean = false,
    val error: AlertUiError? = null,
)

class AlertRulesViewModel(
    initialPlantId: String?,
    private val repository: AlertRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AlertRulesUiState(selectedPlantId = initialPlantId))
    val state: StateFlow<AlertRulesUiState> = mutableState.asStateFlow()
    private var rulesJob: Job? = null
    private var observedPlantId: String? = null
    private var serverRules: List<AlertRuleResponse> = emptyList()

    init {
        viewModelScope.launch {
            repository.observePlants().collect { plants ->
                val selected = mutableState.value.selectedPlantId
                    ?.takeIf { id -> plants.any { it.id == id } }
                    ?: plants.firstOrNull()?.id
                mutableState.update { it.copy(plants = plants, selectedPlantId = selected) }
                if (selected != null && selected != observedPlantId) observeRules(selected)
            }
        }
        viewModelScope.launch {
            val result = repository.refreshAlerts()
            if (mutableState.value.selectedPlantId == null) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = (result as? RefreshResult.PreservedCache)?.cause?.toAlertError(),
                    )
                }
            }
        }
    }

    fun selectPlant(plantId: String) {
        if (plantId == mutableState.value.selectedPlantId) return
        mutableState.update { it.copy(selectedPlantId = plantId, error = null, saved = false, isDirty = false) }
        observeRules(plantId)
    }

    private fun observeRules(plantId: String) {
        rulesJob?.cancel()
        observedPlantId = plantId
        serverRules = emptyList()
        rulesJob = viewModelScope.launch {
            launch {
                repository.observeRules(plantId).collectLatest { rules ->
                    serverRules = rules
                    if (!mutableState.value.isDirty) {
                        mutableState.update { it.copy(drafts = rules.toDrafts()) }
                    }
                }
            }
            refresh()
        }
    }

    fun refresh() {
        val plantId = mutableState.value.selectedPlantId ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null, saved = false) }
            when (val result = repository.refreshRules(plantId)) {
                RefreshResult.Updated -> mutableState.update {
                    it.copy(isLoading = false, isCached = false, error = null)
                }
                is RefreshResult.PreservedCache -> mutableState.update {
                    it.copy(
                        isLoading = false,
                        isCached = serverRules.isNotEmpty(),
                        error = result.cause.toAlertError(),
                    )
                }
            }
        }
    }

    fun configure(type: AlertType) = updateDraft(type) { it.copy(configured = true) }
    fun setEnabled(type: AlertType, enabled: Boolean) = updateDraft(type) { it.copy(enabled = enabled) }
    fun setThreshold(type: AlertType, value: String) = updateDraft(type) {
        it.copy(threshold = value.filter { char -> char.isDigit() || char == '-' || char == '.' }.take(16))
    }
    fun setRequiredDuration(type: AlertType, value: String) = updateDraft(type) {
        it.copy(requiredDurationSeconds = value.filter(Char::isDigit).take(7))
    }
    fun setRecoveryDuration(type: AlertType, value: String) = updateDraft(type) {
        it.copy(recoveryDurationSeconds = value.filter(Char::isDigit).take(7))
    }

    fun reset() {
        mutableState.update {
            it.copy(drafts = serverRules.toDrafts(), isDirty = false, saved = false, error = null)
        }
    }

    fun save() {
        val current = mutableState.value
        val plantId = current.selectedPlantId ?: return
        if (current.isSaving) return
        val requests = try {
            current.drafts.filter { it.configured }.map(AlertRuleDraft::validatedRequest)
        } catch (failure: InvalidRule) {
            mutableState.update { it.copy(error = failure.error, saved = false) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, error = null, saved = false) }
            try {
                val saved = repository.saveRules(plantId, requests)
                serverRules = saved
                mutableState.update {
                    it.copy(
                        drafts = saved.toDrafts(),
                        isSaving = false,
                        isCached = false,
                        isDirty = false,
                        saved = true,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(isSaving = false, saved = false, error = failure.toAlertError())
                }
            }
        }
    }

    private fun updateDraft(type: AlertType, transform: (AlertRuleDraft) -> AlertRuleDraft) {
        mutableState.update { state ->
            state.copy(
                drafts = state.drafts.map { if (it.type == type) transform(it) else it },
                isDirty = true,
                saved = false,
                error = null,
            )
        }
    }
}

internal class InvalidRule(val error: AlertUiError) : IllegalArgumentException()

internal fun AlertRuleDraft.validatedRequest(): PutAlertRuleRequest {
    val parsedThreshold = threshold.toDoubleOrNull()
    if (parsedThreshold == null || !parsedThreshold.isFinite() ||
        (type == AlertType.LOW_SOIL_MOISTURE && parsedThreshold !in 0.0..100.0) ||
        (type == AlertType.DEVICE_OFFLINE && parsedThreshold <= 0.0)
    ) throw InvalidRule(AlertUiError.INVALID_THRESHOLD)
    val required = requiredDurationSeconds.toLongOrNull()
        ?: throw InvalidRule(AlertUiError.INVALID_DURATION)
    val recovery = recoveryDurationSeconds.toLongOrNull()
        ?: throw InvalidRule(AlertUiError.INVALID_DURATION)
    if (required !in 0L..2_592_000L || recovery !in 0L..2_592_000L) {
        throw InvalidRule(AlertUiError.INVALID_DURATION)
    }
    return PutAlertRuleRequest(type, parsedThreshold, required, recovery, enabled)
}

private fun List<AlertRuleResponse>.toDrafts(): List<AlertRuleDraft> = AlertType.entries.map { type ->
    firstOrNull { it.type == type }?.let {
        AlertRuleDraft(
            type = type,
            configured = true,
            threshold = it.threshold?.toString().orEmpty(),
            requiredDurationSeconds = it.requiredDurationSeconds.toString(),
            recoveryDurationSeconds = it.recoveryDurationSeconds.toString(),
            enabled = it.enabled,
        )
    } ?: AlertRuleDraft(type)
}

private fun Throwable.toAlertError(): AlertUiError = when (this) {
    is ApiException.Connectivity, is ApiException.Timeout -> AlertUiError.OFFLINE
    is ApiException.Backend -> when (errorCode) {
        "INVALID_THRESHOLD" -> AlertUiError.INVALID_THRESHOLD
        "INVALID_DURATION" -> AlertUiError.INVALID_DURATION
        "NOT_FOUND" -> AlertUiError.NOT_FOUND
        else -> AlertUiError.SERVER
    }
    else -> AlertUiError.SERVER
}
