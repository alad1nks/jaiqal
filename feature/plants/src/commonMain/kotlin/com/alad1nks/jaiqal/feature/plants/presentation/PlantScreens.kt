package com.alad1nks.jaiqal.feature.plants.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.core.designsystem.component.EmptyState
import com.alad1nks.jaiqal.core.designsystem.component.ErrorState
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalButton
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalTextField
import com.alad1nks.jaiqal.core.designsystem.component.LoadingState
import com.alad1nks.jaiqal.core.designsystem.component.MetricCard
import com.alad1nks.jaiqal.core.designsystem.component.OfflineBanner
import com.alad1nks.jaiqal.core.designsystem.component.StatusBadge
import com.alad1nks.jaiqal.core.designsystem.component.StatusKind
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.format.localizedDecimal
import com.alad1nks.jaiqal.core.designsystem.format.localizedTimestamp
import com.alad1nks.jaiqal.feature.plants.domain.PlantOverview
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

object PlantUiTags {
    const val EMPTY = "plants.empty"
    const val LIST = "plants.list"
    const val CARD = "plants.card"
    const val DETAILS = "plants.details"
    const val MISSING_READINGS = "plants.missing-readings"
    const val OFFLINE_CACHE = "plants.offline-cache"
    const val FORM = "plants.form"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantsScreen(
    onOpenPlant: (String) -> Unit,
    onCreatePlant: () -> Unit,
    onClaimDevice: () -> Unit,
    viewModel: PlantsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.isInitialLoading && state.plants.isEmpty() -> LoadingState(
                stringResource(Res.string.loading),
                Modifier.fillMaxSize(),
            )
            state.plants.isEmpty() && state.error != null -> ErrorState(
                title = stringResource(Res.string.plants_load_error_title),
                message = stringResource(Res.string.plants_load_error_message),
                retryLabel = stringResource(Res.string.retry),
                onRetry = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            )
            state.plants.isEmpty() -> PlantsEmptyState(onCreatePlant, onClaimDevice)
            else -> Column(Modifier.fillMaxSize()) {
                if (state.isCached) {
                    OfflineBanner(stringResource(Res.string.cached_data), Modifier.testTag(PlantUiTags.OFFLINE_CACHE))
                    TextButton(onClick = viewModel::refresh, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(Res.string.retry))
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag(PlantUiTags.LIST),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(JaiqalTheme.spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
                ) {
                    items(state.plants, key = { it.plant.id }) { plant ->
                        PlantCard(plant, onClick = { onOpenPlant(plant.plant.id) })
                    }
                    item {
                        JaiqalButton(
                            text = stringResource(Res.string.add_plant),
                            onClick = onCreatePlant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlantsEmptyState(onCreatePlant: () -> Unit, onClaimDevice: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(PlantUiTags.EMPTY).padding(JaiqalTheme.spacing.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = stringResource(Res.string.plants_empty_title),
            message = stringResource(Res.string.plants_empty_message),
        )
        JaiqalButton(stringResource(Res.string.add_plant), onCreatePlant, Modifier.fillMaxWidth())
        Spacer(Modifier.height(JaiqalTheme.spacing.small))
        OutlinedButton(onClick = onClaimDevice, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.claim_device))
        }
    }
}

@Composable
internal fun PlantCard(overview: PlantOverview, onClick: () -> Unit) {
    JaiqalCard(Modifier.fillMaxWidth().testTag(PlantUiTags.CARD).clickable(onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium)) {
            PlantImagePlaceholder(Modifier.size(76.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    overview.plant.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                overview.plant.species?.let {
                    Text(stringResource(Res.string.species_value, it), style = MaterialTheme.typography.bodyMedium)
                }
                DeviceStatus(overview)
            }
        }
        Spacer(Modifier.height(JaiqalTheme.spacing.medium))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                overview.latest?.soilMoisturePercent?.let {
                    stringResource(Res.string.percent_value, localizedDecimal(it))
                }
                    ?: stringResource(Res.string.no_measurements),
                style = MaterialTheme.typography.titleMedium,
            )
            StatusBadge(
                text = if (overview.activeAlerts.isEmpty()) {
                    stringResource(Res.string.no_active_alerts)
                } else {
                    stringResource(Res.string.active_alerts, overview.activeAlerts.size)
                },
                kind = if (overview.activeAlerts.isEmpty()) StatusKind.SUCCESS else StatusKind.WARNING,
            )
        }
        overview.latest?.let {
            Text(
                if (it.online) stringResource(Res.string.last_measurement, localizedTimestamp(it.measuredAt))
                else stringResource(Res.string.stale_measurement, localizedTimestamp(it.measuredAt)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeviceStatus(overview: PlantOverview) {
    val latest = overview.latest
    val text = when {
        overview.device == null -> stringResource(Res.string.device_not_linked)
        latest == null -> stringResource(Res.string.no_measurements)
        latest.online -> stringResource(Res.string.device_online)
        else -> stringResource(Res.string.device_offline)
    }
    val kind = when {
        latest?.online == true -> StatusKind.SUCCESS
        overview.device != null -> StatusKind.WARNING
        else -> StatusKind.NEUTRAL
    }
    StatusBadge(text, kind)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailsScreen(
    plantId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onClaimDevice: () -> Unit,
    onDeviceDetails: (String) -> Unit,
    onCalibrate: (String) -> Unit,
    viewModel: PlantDetailsViewModel = koinViewModel { parametersOf(plantId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onForeground()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onBackground()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.details?.overview?.plant?.name ?: stringResource(Res.string.plant_details)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) } },
                actions = { TextButton(onClick = onEdit) { Text(stringResource(Res.string.edit_plant)) } },
            )
        },
    ) { padding ->
        when {
            state.isInitialLoading && state.details == null -> LoadingState(
                stringResource(Res.string.loading),
                Modifier.fillMaxSize().padding(padding),
            )
            state.details == null -> ErrorState(
                title = stringResource(Res.string.plants_load_error_title),
                message = plantErrorMessage(state.error),
                retryLabel = stringResource(Res.string.back),
                onRetry = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> PlantDetailsContent(
                details = state.details!!,
                cached = state.isCached,
                selectedRange = state.selectedRange,
                historyLoading = state.isHistoryLoading,
                historyError = state.historyError,
                onSelectHistoryRange = viewModel::selectHistoryRange,
                onRetryHistory = viewModel::retryHistory,
                onClaimDevice = onClaimDevice,
                onDeviceDetails = onDeviceDetails,
                onCalibrate = onCalibrate,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
internal fun PlantDetailsContent(
    details: com.alad1nks.jaiqal.feature.plants.domain.PlantDetails,
    cached: Boolean,
    selectedRange: com.alad1nks.jaiqal.feature.plants.domain.HistoryRange,
    historyLoading: Boolean,
    historyError: PlantUiError?,
    onSelectHistoryRange: (com.alad1nks.jaiqal.feature.plants.domain.HistoryRange) -> Unit,
    onRetryHistory: () -> Unit,
    onClaimDevice: () -> Unit,
    onDeviceDetails: (String) -> Unit,
    onCalibrate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = details.overview
    val latest = overview.latest
    Column(modifier.fillMaxSize().testTag(PlantUiTags.DETAILS).verticalScroll(rememberScrollState())) {
        if (cached) OfflineBanner(stringResource(Res.string.cached_data), Modifier.testTag(PlantUiTags.OFFLINE_CACHE))
        Column(
            Modifier.padding(JaiqalTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium)) {
                PlantImagePlaceholder(Modifier.size(112.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(overview.plant.name, style = MaterialTheme.typography.headlineSmall)
                    overview.plant.species?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    DeviceStatus(overview)
                }
            }
            val measuredAt = latest?.measuredAt?.let { localizedTimestamp(it) }
            val support = when {
                measuredAt == null -> stringResource(Res.string.no_measurements)
                latest.online -> stringResource(Res.string.last_measurement, measuredAt)
                else -> stringResource(Res.string.stale_measurement, measuredAt)
            }
            Column(Modifier.then(if (latest == null) Modifier.testTag(PlantUiTags.MISSING_READINGS) else Modifier)) {
                MetricWithAlert(
                    stringResource(Res.string.soil_moisture),
                    latest?.soilMoisturePercent?.let {
                        stringResource(Res.string.percent_value, localizedDecimal(it))
                    }
                        ?: latest?.soilMoistureRaw?.let { stringResource(Res.string.raw_value, it) }
                        ?: stringResource(Res.string.not_available_short),
                    support,
                    warning = overview.activeAlerts.any { it.type == AlertType.LOW_SOIL_MOISTURE },
                )
                MetricWithAlert(
                    stringResource(Res.string.air_temperature),
                    latest?.airTemperatureCelsius?.let {
                        stringResource(Res.string.temperature_value, localizedDecimal(it))
                    }
                        ?: stringResource(Res.string.not_available_short),
                    support,
                    warning = overview.activeAlerts.any {
                        it.type == AlertType.HIGH_TEMPERATURE || it.type == AlertType.LOW_TEMPERATURE
                    },
                )
                MetricCard(
                    stringResource(Res.string.air_humidity),
                    latest?.airHumidityPercent?.let {
                        stringResource(Res.string.percent_value, localizedDecimal(it))
                    }
                        ?: stringResource(Res.string.not_available_short),
                    support,
                )
                MetricCard(
                    stringResource(Res.string.light_level),
                    latest?.lightRaw?.let { stringResource(Res.string.light_raw_value, it) }
                        ?: stringResource(Res.string.not_available_short),
                    support,
                )
            }
            latest?.let {
                StatusBadge(
                    text = stringResource(
                        if (it.calibrated) Res.string.sensor_calibrated else Res.string.sensor_not_calibrated,
                    ),
                    kind = if (it.calibrated) StatusKind.SUCCESS else StatusKind.NEUTRAL,
                )
            }
            AlertsSummary(overview)
            PlantHistorySection(
                history = details.history,
                selectedRange = selectedRange,
                loading = historyLoading,
                error = historyError,
                onSelectRange = onSelectHistoryRange,
                onRetry = onRetryHistory,
            )
            if (overview.device == null) {
                JaiqalButton(stringResource(Res.string.claim_device), onClaimDevice, Modifier.fillMaxWidth())
            } else {
                OutlinedButton(
                    onClick = { onDeviceDetails(overview.device.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.open_device_details))
                }
                OutlinedButton(
                    onClick = { onCalibrate(overview.device.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.calibrate_device))
                }
            }
        }
    }
}

@Composable
private fun MetricWithAlert(
    label: String,
    value: String,
    supportingText: String,
    warning: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricCard(label, value, supportingText, Modifier.fillMaxWidth())
        if (warning) StatusBadge(stringResource(Res.string.metric_warning), StatusKind.WARNING)
    }
}

@Composable
private fun AlertsSummary(overview: PlantOverview) {
    JaiqalCard(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.alerts), style = MaterialTheme.typography.titleMedium)
        if (overview.activeAlerts.isEmpty()) {
            Text(stringResource(Res.string.no_active_alerts))
        } else {
            overview.activeAlerts.forEach { alert ->
                StatusBadge(alert.type.label(), StatusKind.WARNING, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlantScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: CreatePlantViewModel = koinViewModel(),
) = PlantFormScreen(
    title = stringResource(Res.string.create_plant),
    onBack = onBack,
    onSaved = onSaved,
    viewModel = viewModel,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPlantScreen(
    plantId: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: EditPlantViewModel = koinViewModel { parametersOf(plantId) },
) = PlantFormScreen(
    title = stringResource(Res.string.edit_plant),
    onBack = onBack,
    onSaved = onSaved,
    viewModel = viewModel,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlantFormScreen(
    title: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: PlantFormViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedPlantId) { state.savedPlantId?.let(onSaved) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(JaiqalTheme.spacing.medium)
                .testTag(PlantUiTags.FORM)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
        ) {
            JaiqalTextField(
                state.name,
                viewModel::setName,
                stringResource(Res.string.plant_name),
                enabled = !state.isLoading,
                isError = state.error == PlantUiError.INVALID_NAME,
            )
            JaiqalTextField(
                state.species,
                viewModel::setSpecies,
                stringResource(Res.string.plant_species),
                enabled = !state.isLoading,
                isError = state.error == PlantUiError.INVALID_SPECIES,
            )
            JaiqalTextField(
                state.imageUrl,
                viewModel::setImageUrl,
                stringResource(Res.string.plant_image_url),
                enabled = !state.isLoading,
                isError = state.error == PlantUiError.INVALID_IMAGE_URL,
            )
            state.error?.let { Text(plantErrorMessage(it), color = MaterialTheme.colorScheme.error) }
            JaiqalButton(
                stringResource(Res.string.save),
                viewModel::save,
                Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            )
            if (state.isLoading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun PlantImagePlaceholder(modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.plant_placeholder_description)
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(Res.string.plant_placeholder_symbol), style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun plantErrorMessage(error: PlantUiError?): String = stringResource(
    when (error) {
        PlantUiError.OFFLINE -> Res.string.plant_offline_mutation_error
        PlantUiError.INVALID_NAME -> Res.string.plant_name_error
        PlantUiError.INVALID_SPECIES -> Res.string.plant_species_error
        PlantUiError.INVALID_IMAGE_URL -> Res.string.plant_image_url_error
        PlantUiError.NOT_FOUND -> Res.string.plant_not_found_error
        PlantUiError.SERVER, null -> Res.string.plant_server_error
    },
)

@Composable
private fun AlertType.label(): String = when (this) {
    AlertType.LOW_SOIL_MOISTURE -> stringResource(Res.string.soil_moisture)
    AlertType.HIGH_TEMPERATURE, AlertType.LOW_TEMPERATURE -> stringResource(Res.string.air_temperature)
    AlertType.DEVICE_OFFLINE -> stringResource(Res.string.device_offline)
}
