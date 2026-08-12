package com.alad1nks.jaiqal.feature.alerts.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.core.designsystem.component.EmptyState
import com.alad1nks.jaiqal.core.designsystem.component.ErrorState
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalTextField
import com.alad1nks.jaiqal.core.designsystem.component.LoadingState
import com.alad1nks.jaiqal.core.designsystem.component.OfflineBanner
import com.alad1nks.jaiqal.core.designsystem.component.StatusBadge
import com.alad1nks.jaiqal.core.designsystem.component.StatusKind
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.feature.alerts.domain.AlertOverview
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AlertsScreen(
    onOpenRules: () -> Unit,
    viewModel: AlertsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.isInitialLoading && state.alerts.isEmpty() -> LoadingState(
            stringResource(Res.string.loading),
            Modifier.fillMaxSize(),
        )
        state.alerts.isEmpty() && state.error != null -> ErrorState(
            title = stringResource(Res.string.alerts_load_error_title),
            message = alertErrorMessage(state.error!!),
            retryLabel = stringResource(Res.string.retry),
            onRetry = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(JaiqalTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.alerts), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onOpenRules) { Text(stringResource(Res.string.alert_rules)) }
                }
            }
            if (state.isCached) item { OfflineBanner(stringResource(Res.string.offline)) }
            if (state.error != null && state.alerts.isNotEmpty()) {
                item { OfflineBanner(alertErrorMessage(state.error!!)) }
            }
            if (state.isRefreshing) item { Text(stringResource(Res.string.refreshing)) }
            if (state.alerts.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(Res.string.alerts_empty_title),
                        message = stringResource(Res.string.alerts_empty_message),
                        action = stringResource(Res.string.alert_rules),
                        onAction = onOpenRules,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(state.alerts, key = { it.event.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        acknowledging = alert.event.id in state.acknowledgingIds,
                        onAcknowledge = { viewModel.acknowledge(alert) },
                    )
                }
            }
            item {
                OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: AlertOverview, acknowledging: Boolean, onAcknowledge: () -> Unit) {
    val event = alert.event
    JaiqalCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(alertTypeResource(event.type)), style = MaterialTheme.typography.titleMedium)
            StatusBadge(
                text = stringResource(
                    if (event.status == AlertStatus.ACTIVE) Res.string.alert_active else Res.string.alert_resolved,
                ),
                kind = if (event.status == AlertStatus.ACTIVE) StatusKind.ERROR else StatusKind.SUCCESS,
            )
        }
        Text(stringResource(Res.string.alert_plant_value, alert.plantName))
        Text(stringResource(Res.string.alert_triggered_value, event.triggeredAt))
        event.recoveredAt?.let { Text(stringResource(Res.string.alert_recovered_value, it)) }
        Text(stringResource(Res.string.alert_value_unavailable), style = MaterialTheme.typography.bodySmall)
        if (event.acknowledgedAt == null) {
            JaiqalButton(
                text = stringResource(Res.string.acknowledge_alert),
                onClick = onAcknowledge,
                modifier = Modifier.fillMaxWidth(),
                enabled = !acknowledging,
            )
            if (acknowledging) CircularProgressIndicator()
        } else {
            val acknowledgedAt = event.acknowledgedAt.orEmpty()
            Text(stringResource(Res.string.alert_acknowledged_value, acknowledgedAt))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertRulesScreen(
    initialPlantId: String?,
    onBack: () -> Unit,
    viewModel: AlertRulesViewModel = koinViewModel { parametersOf(initialPlantId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.alert_rules)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        if (state.plants.isEmpty() && !state.isLoading) {
            EmptyState(
                title = stringResource(Res.string.alert_rules_no_plants_title),
                message = stringResource(Res.string.alert_rules_no_plants_message),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(JaiqalTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.small)) {
                        Text(stringResource(Res.string.select_plant), style = MaterialTheme.typography.titleMedium)
                        state.plants.forEach { plant ->
                            OutlinedButton(
                                onClick = { viewModel.selectPlant(plant.id) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = plant.id != state.selectedPlantId && !state.isSaving,
                            ) { Text(plant.name) }
                        }
                    }
                }
                if (state.isCached) item { OfflineBanner(stringResource(Res.string.offline)) }
                item { Text(stringResource(Res.string.alert_duration_explanation)) }
                items(state.drafts, key = { it.type.name }) { draft ->
                    AlertRuleCard(draft, state.isSaving, viewModel)
                }
                state.error?.let { error ->
                    item { StatusBadge(alertErrorMessage(error), StatusKind.ERROR) }
                }
                if (state.saved) item { StatusBadge(stringResource(Res.string.alert_rules_saved), StatusKind.SUCCESS) }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.small),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::reset,
                            modifier = Modifier.weight(1f),
                            enabled = state.isDirty && !state.isSaving,
                        ) { Text(stringResource(Res.string.reset_server_values)) }
                        JaiqalButton(
                            text = stringResource(Res.string.save),
                            onClick = viewModel::save,
                            modifier = Modifier.weight(1f),
                            enabled = state.isDirty && !state.isSaving && state.selectedPlantId != null,
                        )
                    }
                }
                if (state.isLoading || state.isSaving) {
                    item { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun AlertRuleCard(draft: AlertRuleDraft, saving: Boolean, viewModel: AlertRulesViewModel) {
    JaiqalCard(Modifier.fillMaxWidth()) {
        Text(stringResource(alertTypeResource(draft.type)), style = MaterialTheme.typography.titleMedium)
        if (!draft.configured) {
            Text(stringResource(Res.string.alert_rule_not_configured))
            OutlinedButton(onClick = { viewModel.configure(draft.type) }, enabled = !saving) {
                Text(stringResource(Res.string.configure_alert_rule))
            }
            return@JaiqalCard
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.alert_rule_enabled))
            Switch(
                checked = draft.enabled,
                onCheckedChange = { viewModel.setEnabled(draft.type, it) },
                enabled = !saving,
            )
        }
        JaiqalTextField(
            value = draft.threshold,
            onValueChange = { viewModel.setThreshold(draft.type, it) },
            label = stringResource(alertThresholdResource(draft.type)),
            enabled = !saving,
        )
        JaiqalTextField(
            value = draft.requiredDurationSeconds,
            onValueChange = { viewModel.setRequiredDuration(draft.type, it) },
            label = stringResource(Res.string.alert_required_duration),
            enabled = !saving,
        )
        JaiqalTextField(
            value = draft.recoveryDurationSeconds,
            onValueChange = { viewModel.setRecoveryDuration(draft.type, it) },
            label = stringResource(Res.string.alert_recovery_duration),
            enabled = !saving,
        )
    }
}

private fun alertTypeResource(type: AlertType): StringResource = when (type) {
    AlertType.LOW_SOIL_MOISTURE -> Res.string.alert_type_low_soil
    AlertType.HIGH_TEMPERATURE -> Res.string.alert_type_high_temperature
    AlertType.LOW_TEMPERATURE -> Res.string.alert_type_low_temperature
    AlertType.DEVICE_OFFLINE -> Res.string.alert_type_device_offline
}

private fun alertThresholdResource(type: AlertType): StringResource = when (type) {
    AlertType.LOW_SOIL_MOISTURE -> Res.string.alert_threshold_percent
    AlertType.HIGH_TEMPERATURE, AlertType.LOW_TEMPERATURE -> Res.string.alert_threshold_temperature
    AlertType.DEVICE_OFFLINE -> Res.string.alert_threshold_offline_seconds
}

@Composable
private fun alertErrorMessage(error: AlertUiError): String = stringResource(
    when (error) {
        AlertUiError.OFFLINE -> Res.string.alert_offline_error
        AlertUiError.INVALID_THRESHOLD -> Res.string.alert_invalid_threshold
        AlertUiError.INVALID_DURATION -> Res.string.alert_invalid_duration
        AlertUiError.NOT_FOUND -> Res.string.alert_not_found
        AlertUiError.SERVER -> Res.string.alert_server_error
    },
)
