package com.alad1nks.jaiqal.feature.plants.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryPoint
import com.alad1nks.jaiqal.api.contract.PlantHistoryResponse
import com.alad1nks.jaiqal.core.designsystem.component.JaiqalCard
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import com.alad1nks.jaiqal.core.designsystem.format.localizedDecimal
import com.alad1nks.jaiqal.core.designsystem.format.localizedTimestamp
import com.alad1nks.jaiqal.feature.plants.domain.HistoryRange
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.*
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

private enum class HistoryMetric(
    val unit: String,
    val value: (PlantHistoryPoint) -> Double?,
) {
    SOIL("%", PlantHistoryPoint::soilMoisturePercent),
    TEMPERATURE("°C", PlantHistoryPoint::airTemperatureCelsius),
    HUMIDITY("%", PlantHistoryPoint::airHumidityPercent),
    LIGHT("ADC", PlantHistoryPoint::lightRaw),
}

internal data class HistoryChartSample(val measuredAt: Instant, val value: Double)

internal fun historyChartSegments(
    points: List<PlantHistoryPoint>,
    interval: HistoryInterval,
    value: (PlantHistoryPoint) -> Double?,
): List<List<HistoryChartSample>> {
    val expectedGapMillis = when (interval) {
        HistoryInterval.RAW, HistoryInterval.FIVE_MINUTES -> 5 * 60 * 1_000L
        HistoryInterval.ONE_HOUR -> 60 * 60 * 1_000L
        HistoryInterval.ONE_DAY -> 24 * 60 * 60 * 1_000L
    }
    val maximumConnectedGap = expectedGapMillis * 5 / 2
    val segments = mutableListOf<MutableList<HistoryChartSample>>()
    var previousTimestamp: Long? = null
    var missingSincePrevious = false
    points.sortedBy(PlantHistoryPoint::measuredAt).forEach { point ->
        val timestamp = runCatching { Instant.parse(point.measuredAt) }.getOrNull()
        val metricValue = value(point)
        if (timestamp == null || metricValue == null || !metricValue.isFinite()) {
            missingSincePrevious = true
            return@forEach
        }
        val millis = timestamp.toEpochMilliseconds()
        val startsNewSegment = missingSincePrevious || previousTimestamp?.let { millis - it > maximumConnectedGap } != false
        if (startsNewSegment) segments.add(mutableListOf())
        segments.last().add(HistoryChartSample(timestamp, metricValue))
        previousTimestamp = millis
        missingSincePrevious = false
    }
    return segments
}

@Composable
internal fun PlantHistorySection(
    history: PlantHistoryResponse?,
    selectedRange: HistoryRange,
    loading: Boolean,
    error: PlantUiError?,
    onSelectRange: (HistoryRange) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium)) {
        Text(stringResource(Res.string.measurement_history), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryRange.entries.forEach { range ->
                FilterChip(
                    selected = selectedRange == range,
                    onClick = { onSelectRange(range) },
                    label = { Text(range.label()) },
                    enabled = !loading,
                )
            }
        }
        when {
            loading && history == null -> CircularProgressIndicator()
            error != null && history == null -> {
                Text(plantHistoryErrorMessage(error), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
            }
            history == null || history.points.isEmpty() -> Text(stringResource(Res.string.history_empty))
            else -> {
                if (loading) Text(stringResource(Res.string.history_refreshing))
                if (error != null) {
                    Text(plantHistoryErrorMessage(error), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
                }
                HistoryMetric.entries.forEach { metric ->
                    HistoryLineChart(history, metric)
                }
            }
        }
    }
}

@Composable
private fun HistoryLineChart(history: PlantHistoryResponse, metric: HistoryMetric) {
    val usesRawSoil = metric == HistoryMetric.SOIL && history.points.none { it.soilMoisturePercent != null }
    val valueSelector = if (usesRawSoil) PlantHistoryPoint::soilMoistureRaw else metric.value
    val unit = if (usesRawSoil) "ADC" else metric.unit
    val segments = historyChartSegments(history.points, history.interval, valueSelector)
    val samples = segments.flatten()
    val title = metric.label()
    JaiqalCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (samples.isEmpty()) {
            Text(stringResource(Res.string.metric_has_no_data))
            return@JaiqalCard
        }
        val minimum = samples.minOf(HistoryChartSample::value)
        val maximum = samples.maxOf(HistoryChartSample::value)
        val start = samples.minOf(HistoryChartSample::measuredAt)
        val end = samples.maxOf(HistoryChartSample::measuredAt)
        val description = stringResource(
            Res.string.chart_accessibility,
            title,
            samples.size,
            localizedDecimal(minimum),
            localizedDecimal(maximum),
            unit,
        )
        val lineColor = MaterialTheme.colorScheme.primary
        val axisColor = MaterialTheme.colorScheme.outline
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp).semantics {
                contentDescription = description
            },
        ) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 1.dp.toPx())
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
            val timeSpan = (end.toEpochMilliseconds() - start.toEpochMilliseconds()).coerceAtLeast(1L)
            val valueSpan = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
            segments.forEach { segment ->
                val path = Path()
                segment.forEachIndexed { index, sample ->
                    val x = left + (right - left) *
                        ((sample.measuredAt.toEpochMilliseconds() - start.toEpochMilliseconds()).toFloat() / timeSpan)
                    val y = bottom - (bottom - top) * ((sample.value - minimum).toFloat() / valueSpan.toFloat())
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(x, y))
                }
                if (segment.size > 1) drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(localizedTimestamp(start), style = MaterialTheme.typography.labelSmall)
            Text(localizedTimestamp(end), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            stringResource(
                Res.string.chart_range,
                localizedDecimal(minimum),
                localizedDecimal(maximum),
                unit,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HistoryRange.label(): String = stringResource(
    when (this) {
        HistoryRange.LAST_24_HOURS -> Res.string.range_24_hours
        HistoryRange.LAST_7_DAYS -> Res.string.range_7_days
        HistoryRange.LAST_30_DAYS -> Res.string.range_30_days
    },
)

@Composable
private fun HistoryMetric.label(): String = stringResource(
    when (this) {
        HistoryMetric.SOIL -> Res.string.soil_moisture
        HistoryMetric.TEMPERATURE -> Res.string.air_temperature
        HistoryMetric.HUMIDITY -> Res.string.air_humidity
        HistoryMetric.LIGHT -> Res.string.light_level
    },
)

@Composable
private fun plantHistoryErrorMessage(error: PlantUiError): String = stringResource(
    if (error == PlantUiError.OFFLINE) Res.string.history_offline_error else Res.string.history_load_error,
)
