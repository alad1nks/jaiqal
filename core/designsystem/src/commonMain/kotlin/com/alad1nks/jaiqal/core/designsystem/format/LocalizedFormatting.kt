package com.alad1nks.jaiqal.core.designsystem.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun localizedDecimal(value: Double, fractionDigits: Int = 1): String =
    formatLocalizedDecimal(value, fractionDigits, Locale.current.language)

@Composable
fun localizedTimestamp(value: String): String = runCatching { Instant.parse(value) }
    .getOrNull()
    ?.let { formatLocalizedTimestamp(it, Locale.current.language) }
    ?: value

@Composable
fun localizedTimestamp(value: Instant): String = formatLocalizedTimestamp(value, Locale.current.language)

internal fun formatLocalizedDecimal(value: Double, fractionDigits: Int, language: String): String {
    val scale = 10.0.pow(fractionDigits)
    val rounded = round(value * scale) / scale
    val text = if (fractionDigits == 0) rounded.toLong().toString() else {
        val raw = rounded.toString()
        val decimals = raw.substringAfter('.', "").padEnd(fractionDigits, '0').take(fractionDigits)
        "${raw.substringBefore('.')}.$decimals"
    }
    return if (language == "ru" || language == "kk") text.replace('.', ',') else text
}

internal fun formatLocalizedTimestamp(instant: Instant, language: String): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val date = if (language == "en") {
        "${(local.month.ordinal + 1).twoDigits()}/${local.day.twoDigits()}/${local.year}"
    } else {
        "${local.day.twoDigits()}.${(local.month.ordinal + 1).twoDigits()}.${local.year}"
    }
    return "$date ${local.hour.twoDigits()}:${local.minute.twoDigits()}"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
