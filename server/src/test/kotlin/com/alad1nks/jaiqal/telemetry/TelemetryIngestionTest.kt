package com.alad1nks.jaiqal.telemetry

import com.alad1nks.jaiqal.api.contract.DeviceMeasurementRequest
import com.alad1nks.jaiqal.config.TelemetryConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryIngestionTest {
    @Test fun `calibration supports normal reversed clamped and equal values`() {
        assertEquals(50.0, calculateSoilMoisturePercent(1500, 2000, 1000))
        assertEquals(50.0, calculateSoilMoisturePercent(1500, 1000, 2000))
        assertEquals(0.0, calculateSoilMoisturePercent(3000, 2000, 1000))
        assertEquals(100.0, calculateSoilMoisturePercent(500, 2000, 1000))
        assertNull(calculateSoilMoisturePercent(1000, 1000, 1000))
        assertNull(calculateSoilMoisturePercent(1000, null, 2000))
    }

    @Test fun `validation rejects invalid values before store is called`() {
        val service = TelemetryIngestionService(TelemetryStore { _, _ -> error("must not run") }, TelemetryConfig(), clock = clock)
        val error = assertFailsWith<TelemetryValidationException> {
            service.ingest(device, listOf(DeviceMeasurementRequest(1, airHumidityPercent = 101.0)))
        }
        assertEquals("INVALID_HUMIDITY", error.errorCode)
    }

    @Test fun `outside timestamp falls back and event is published after store returns`() {
        var storeFinished = false
        var extra = ""
        val events = mutableListOf<MeasurementReceived>()
        val store = TelemetryStore { _, values ->
            extra = values.single().measurement.extra
            storeFinished = true
            listOf(IngestionResult(MeasurementRecord(4, values.single().measurement), false))
        }
        val service = TelemetryIngestionService(store, TelemetryConfig(), MeasurementEventPublisher {
            check(storeFinished); events += it
        }, clock)
        val old = kotlin.time.Instant.parse("2020-01-01T00:00:00Z")
        service.ingest(device, listOf(DeviceMeasurementRequest(2, measuredAt = old, lightRaw = 10)))
        assertEquals(true, "outside_window" in extra)
        assertEquals(1, events.size)
    }

    @Test fun `firmware version accepts exact boundary and is normalized`() {
        var storedFirmware: String? = null
        val store = TelemetryStore { _, values ->
            storedFirmware = values.single().firmwareVersion
            listOf(IngestionResult(MeasurementRecord(1, values.single().measurement), false))
        }
        val service = TelemetryIngestionService(store, TelemetryConfig(), clock = clock)

        service.ingest(
            device,
            listOf(DeviceMeasurementRequest(3, firmwareVersion = "  ${"v".repeat(100)}  ", lightRaw = 10)),
        )

        assertEquals("v".repeat(100), storedFirmware)
    }

    @Test fun `firmware version rejects overflow and control characters before store`() {
        var storeCalled = false
        val service = TelemetryIngestionService(
            TelemetryStore { _, _ -> storeCalled = true; error("must not run") },
            TelemetryConfig(),
            clock = clock,
        )

        listOf("v".repeat(101), "version\n1").forEach { firmwareVersion ->
            val error = assertFailsWith<TelemetryValidationException> {
                service.ingest(
                    device,
                    listOf(DeviceMeasurementRequest(4, firmwareVersion = firmwareVersion, lightRaw = 10)),
                )
            }
            assertEquals("INVALID_FIRMWARE_VERSION", error.errorCode)
        }
        assertTrue(!storeCalled)
    }

    @Test fun `quarantine decision stops persistence with a distinct temporary error`() {
        var storeCalled = false
        val service = TelemetryIngestionService(
            TelemetryStore { _, _ -> storeCalled = true; error("must not run") },
            TelemetryConfig(),
            clock = clock,
            quota = DeviceIngestionQuota { _, _ ->
                IngestionQuotaDecision(allowed = false, retryAfterSeconds = 300, quarantined = true)
            },
        )

        val error = assertFailsWith<DeviceQuarantinedException> {
            service.ingest(device, listOf(DeviceMeasurementRequest(5, lightRaw = 10)))
        }

        assertEquals(300, error.retryAfterSeconds)
        assertEquals(false, storeCalled)
    }

    private val clock = Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC)
    private val device = DeviceRecord(UUID.randomUUID(), null, "test", "hash", soilDryRaw = 2000, soilWetRaw = 1000,
        createdAt = OffsetDateTime.now(clock))
}
