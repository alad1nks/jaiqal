package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.core.auth.validateEmail
import com.alad1nks.jaiqal.core.auth.validatePassword
import com.alad1nks.jaiqal.feature.devices.calibrationError
import com.alad1nks.jaiqal.feature.telemetry.HistoryRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreLogicTest {
    @Test fun validatesFirebaseCredentials() {
        assertTrue(validateEmail("user@example.com"))
        assertFalse(validateEmail("not-an-email"))
        assertTrue(validatePassword("123456"))
        assertFalse(validatePassword("12345"))
    }

    @Test fun calibrationAcceptsEitherAdcDirection() {
        assertNull(calibrationError(3000, 900))
        assertNull(calibrationError(900, 3000))
        assertEquals("CALIBRATION_VALUES_EQUAL", calibrationError(1000, 1000))
    }

    @Test fun longHistoryUsesServerAggregation() {
        assertEquals("raw", HistoryRange.DAY.interval)
        assertEquals("1h", HistoryRange.MONTH.interval)
    }
}
