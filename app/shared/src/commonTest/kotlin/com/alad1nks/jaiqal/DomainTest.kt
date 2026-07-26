package com.alad1nks.jaiqal
import com.alad1nks.jaiqal.core.session.*
import com.alad1nks.jaiqal.domain.*
import kotlin.test.*
class DomainTest {
 @Test fun validatesCredentials(){assertTrue(validEmail("plant@example.com"));assertFalse(validEmail("plant"));assertTrue(validPassword("12345678"));assertFalse(validPassword("short"))}
 @Test fun mapsHistoryPeriods(){assertEquals(MeasurementInterval.FIVE_MINUTES,MeasurementRange.DAY.interval());assertEquals(MeasurementInterval.ONE_HOUR,MeasurementRange.WEEK.interval());assertEquals(MeasurementInterval.ONE_DAY,MeasurementRange.MONTH.interval())}
 @Test fun calibrationUsesMedianAndSupportsReversedAdc(){val normal=calibration(listOf(1000,1002,999),listOf(2000,1998,2001)) as CalibrationResult.Valid;assertEquals(1000,normal.value.dryRaw);assertEquals(50,normal.value.percent(1500));val reversed=calibration(listOf(3000,3001,2999),listOf(1000,1001,999)) as CalibrationResult.Valid;assertEquals(50,reversed.value.percent(2000))}
 @Test fun calibrationRejectsUnsafeSamples(){assertIs<CalibrationResult.InsufficientSamples>(calibration(listOf(1),listOf(2)));assertIs<CalibrationResult.TooClose>(calibration(listOf(100,101,102),listOf(120,121,122)))}
}
