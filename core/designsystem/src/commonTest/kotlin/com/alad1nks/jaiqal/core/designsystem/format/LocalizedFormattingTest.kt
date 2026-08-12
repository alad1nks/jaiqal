package com.alad1nks.jaiqal.core.designsystem.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LocalizedFormattingTest {
    @Test
    fun decimalsUseLocaleSeparatorAndRequestedPrecision() {
        assertEquals("23.50", formatLocalizedDecimal(23.5, 2, "en"))
        assertEquals("23,50", formatLocalizedDecimal(23.5, 2, "ru"))
        assertEquals("23,5", formatLocalizedDecimal(23.54, 1, "kk"))
    }

    @Test
    fun timestampsUseLocaleDateOrder() {
        val instant = Instant.parse("2026-08-12T10:05:00Z")
        val english = formatLocalizedTimestamp(instant, "en")
        val russian = formatLocalizedTimestamp(instant, "ru")

        val (englishMonth, englishDay, englishYear) = english.substringBefore(' ').split('/')
        val (russianDay, russianMonth, russianYear) = russian.substringBefore(' ').split('.')
        assertEquals(englishMonth, russianMonth)
        assertEquals(englishDay, russianDay)
        assertEquals(englishYear, russianYear)
    }
}
