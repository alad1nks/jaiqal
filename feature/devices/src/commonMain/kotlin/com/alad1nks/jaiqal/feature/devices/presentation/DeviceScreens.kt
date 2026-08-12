package com.alad1nks.jaiqal.feature.devices.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alad1nks.jaiqal.core.designsystem.component.ErrorState
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalTextField
import com.alad1nks.jaiqal.core.designsystem.component.LoadingState
import com.alad1nks.jaiqal.core.designsystem.component.OfflineBanner
import com.alad1nks.jaiqal.core.designsystem.component.StatusBadge
import com.alad1nks.jaiqal.core.designsystem.component.StatusKind
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.format.localizedTimestamp
import com.alad1nks.jaiqal.core.designsystem.format.localizedDecimal
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaimDeviceScreen(
    initialPlantId: String?,
    onBack: () -> Unit,
    onClaimed: (String) -> Unit,
    viewModel: ClaimDeviceViewModel = koinViewModel { parametersOf(initialPlantId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.claimedDevice?.id) {
        state.claimedDevice?.id?.let(onClaimed)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.claim_device)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(JaiqalTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
        ) {
            Text(stringResource(Res.string.claim_code_help), style = MaterialTheme.typography.bodyLarge)
            if (state.plants.isEmpty()) {
                StatusBadge(stringResource(Res.string.claim_no_plant), StatusKind.WARNING)
            } else {
                Text(stringResource(Res.string.select_plant), style = MaterialTheme.typography.titleMedium)
                state.plants.forEach { plant ->
                    val selected = plant.id == state.selectedPlantId
                    JaiqalCard(
                        Modifier.fillMaxWidth().semantics { this.selected = selected }
                            .clickable(enabled = !state.isLoading) { viewModel.selectPlant(plant.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(plant.name, style = MaterialTheme.typography.titleMedium)
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.selectPlant(plant.id) },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                }
            }
            JaiqalTextField(
                value = state.claimCode,
                onValueChange = viewModel::setClaimCode,
                label = stringResource(Res.string.claim_code),
                enabled = !state.isLoading && state.plants.isNotEmpty(),
                isError = state.error == DeviceUiError.INVALID_CODE || state.error == DeviceUiError.CODE_UNAVAILABLE,
            )
            state.error?.let { error ->
                if (error == DeviceUiError.RESULT_UNCERTAIN) {
                    OfflineBanner(stringResource(Res.string.claim_result_uncertain))
                } else {
                    StatusBadge(deviceErrorMessage(error), StatusKind.ERROR)
                }
            }
            JaiqalButton(
                text = stringResource(if (state.resultWasUncertain) Res.string.retry else Res.string.claim_submit),
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading && state.plants.isNotEmpty(),
            )
            if (state.isLoading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(
    deviceId: String,
    onBack: () -> Unit,
    onCalibrate: () -> Unit,
    viewModel: DeviceDetailsViewModel = koinViewModel { parametersOf(deviceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.overview?.device?.name ?: stringResource(Res.string.device_details)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.overview == null -> LoadingState(
                stringResource(Res.string.loading),
                Modifier.fillMaxSize().padding(padding),
            )
            state.overview == null -> ErrorState(
                title = stringResource(Res.string.device_details),
                message = state.error?.let { deviceErrorMessage(it) }
                    ?: stringResource(Res.string.device_load_error),
                retryLabel = stringResource(Res.string.retry),
                onRetry = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> DeviceDetailsContent(state, onCalibrate, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DeviceDetailsContent(
    state: DeviceDetailsUiState,
    onCalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = state.overview ?: return
    val device = overview.device
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JaiqalTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
    ) {
        if (state.error != null) OfflineBanner(stringResource(Res.string.device_load_error))
        StatusBadge(
            text = stringResource(if (overview.latest?.online == true) Res.string.device_online else Res.string.device_offline),
            kind = if (overview.latest?.online == true) StatusKind.SUCCESS else StatusKind.WARNING,
        )
        JaiqalCard(Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.device_name_value, device.name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.device_id_value, device.id), style = MaterialTheme.typography.bodySmall)
            overview.plant?.let { Text(stringResource(Res.string.device_plant_value, it.name)) }
            device.firmwareVersion?.let { Text(stringResource(Res.string.firmware_value, it)) }
            device.lastSeenAt?.let { Text(stringResource(Res.string.last_seen_value, localizedTimestamp(it))) }
        }
        val dryRaw = device.soilDryRaw
        val wetRaw = device.soilWetRaw
        if (dryRaw != null && wetRaw != null) {
            StatusBadge(stringResource(Res.string.sensor_calibrated), StatusKind.SUCCESS)
            Text(
                stringResource(
                    Res.string.calibration_values,
                    localizedDecimal(dryRaw.toDouble(), 0),
                    localizedDecimal(wetRaw.toDouble(), 0),
                ),
            )
        } else {
            StatusBadge(stringResource(Res.string.sensor_not_calibrated), StatusKind.NEUTRAL)
        }
        JaiqalButton(
            text = stringResource(Res.string.calibrate_device),
            onClick = onCalibrate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    deviceId: String,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    viewModel: CalibrationViewModel = koinViewModel { parametersOf(deviceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.calibration_title)) },
                navigationIcon = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(JaiqalTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
        ) {
            Text(
                stringResource(Res.string.calibration_progress, state.step.ordinal + 1),
                style = MaterialTheme.typography.labelLarge,
            )
            CalibrationStepContent(state)
            state.error?.let { StatusBadge(deviceErrorMessage(it), StatusKind.ERROR) }
            CalibrationActions(state, viewModel, onCancel)
            if (state.isLoading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun CalibrationStepContent(state: CalibrationUiState) {
    when (state.step) {
        CalibrationStep.INTRODUCTION -> Explanation(
            stringResource(Res.string.calibration_intro_title),
            stringResource(Res.string.calibration_intro_message),
        )
        CalibrationStep.DRY_SAMPLE -> Explanation(
            stringResource(Res.string.calibration_dry_title),
            stringResource(Res.string.calibration_dry_message),
        )
        CalibrationStep.WET_SAMPLE -> {
            Explanation(
                stringResource(Res.string.calibration_wet_title),
                stringResource(Res.string.calibration_wet_message),
            )
            state.drySample?.let { SampleCard(stringResource(Res.string.calibration_dry_title), it) }
        }
        CalibrationStep.REVIEW -> {
            Explanation(
                stringResource(Res.string.calibration_review_title),
                stringResource(Res.string.calibration_review_message),
            )
            state.drySample?.let { SampleCard(stringResource(Res.string.calibration_dry_title), it) }
            state.wetSample?.let { SampleCard(stringResource(Res.string.calibration_wet_title), it) }
            StatusBadge(
                stringResource(
                    if (state.isReversed) Res.string.calibration_reversed_adc
                    else Res.string.calibration_normal_adc,
                ),
                StatusKind.NEUTRAL,
            )
        }
        CalibrationStep.CONFIRMATION -> {
            Explanation(
                stringResource(Res.string.calibration_confirm_title),
                stringResource(Res.string.calibration_confirm_message),
            )
            state.drySample?.let { SampleCard(stringResource(Res.string.calibration_dry_title), it) }
            state.wetSample?.let { SampleCard(stringResource(Res.string.calibration_wet_title), it) }
        }
    }
}

@Composable
private fun Explanation(title: String, message: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SampleCard(label: String, sample: com.alad1nks.jaiqal.feature.devices.domain.CalibrationSample) {
    JaiqalCard(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                Res.string.captured_reading,
                localizedDecimal(sample.raw.toDouble(), 0),
                localizedTimestamp(sample.measuredAt),
            ),
        )
    }
}

@Composable
private fun CalibrationActions(
    state: CalibrationUiState,
    viewModel: CalibrationViewModel,
    onCancel: () -> Unit,
) {
    val primaryLabel = when (state.step) {
        CalibrationStep.INTRODUCTION, CalibrationStep.REVIEW -> Res.string.continue_action
        CalibrationStep.DRY_SAMPLE, CalibrationStep.WET_SAMPLE -> Res.string.capture_reading
        CalibrationStep.CONFIRMATION -> Res.string.send_calibration
    }
    JaiqalButton(
        text = stringResource(primaryLabel),
        onClick = when (state.step) {
            CalibrationStep.INTRODUCTION, CalibrationStep.REVIEW -> viewModel::next
            CalibrationStep.DRY_SAMPLE, CalibrationStep.WET_SAMPLE -> viewModel::capture
            CalibrationStep.CONFIRMATION -> viewModel::confirm
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.small)) {
        if (state.step != CalibrationStep.INTRODUCTION) {
            OutlinedButton(onClick = viewModel::back, enabled = !state.isLoading, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.back))
            }
        }
        TextButton(onClick = onCancel, enabled = !state.isLoading, modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.cancel))
        }
    }
}

@Composable
private fun deviceErrorMessage(error: DeviceUiError): String = stringResource(
    when (error) {
        DeviceUiError.INVALID_CODE -> Res.string.claim_invalid_code
        DeviceUiError.CODE_UNAVAILABLE -> Res.string.claim_code_unavailable
        DeviceUiError.RESULT_UNCERTAIN -> Res.string.claim_result_uncertain
        DeviceUiError.OFFLINE -> Res.string.device_network_error
        DeviceUiError.DEVICE_OFFLINE -> Res.string.device_offline_error
        DeviceUiError.MEASUREMENT_UNAVAILABLE -> Res.string.measurement_unavailable_error
        DeviceUiError.INVALID_CALIBRATION -> Res.string.calibration_equal_error
        DeviceUiError.NO_PLANT -> Res.string.claim_no_plant
        DeviceUiError.NOT_FOUND -> Res.string.device_not_found_error
        DeviceUiError.SERVER -> Res.string.device_server_error
    },
)
