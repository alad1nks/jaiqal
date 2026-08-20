package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.api.contract.DeviceMeasurementResponse
import com.alad1nks.jaiqal.auth.DevicePrincipal
import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.config.HttpLimitConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.telemetry.IngestionResult
import com.alad1nks.jaiqal.telemetry.MeasurementRecord
import com.alad1nks.jaiqal.telemetry.TelemetryIngestionService
import com.alad1nks.jaiqal.telemetry.TelemetryStore
import com.alad1nks.jaiqal.telemetry.DeviceIngestionQuota
import com.alad1nks.jaiqal.telemetry.IngestionQuotaDecision
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.OffsetDateTime
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class TelemetryRoutesTest {
    private val id = UUID.randomUUID()
    private val device = DeviceRecord(id, null, "sensor", "hash", createdAt = OffsetDateTime.now())
    private val repository = object : DeviceRepository {
        override fun create(device: DeviceRecord) = device
        override fun findById(id: UUID) = device.takeIf { it.id == id }
        override fun findByPlantId(plantId: UUID) = emptyList<DeviceRecord>()
    }
    private val json = Json

    @Test fun `valid token uploads and duplicate is successful`() = testApplication {
        var inserted = false
        val store = TelemetryStore { _, values ->
            if (inserted) listOf(IngestionResult(null, true)) else {
                inserted = true; listOf(IngestionResult(MeasurementRecord(1, values.single().measurement), false))
            }
        }
        application { configureApplication(config(), { true }, DeviceTokenAuthenticator { DevicePrincipal(id) }, repository, TelemetryIngestionService(store, config().telemetry)) }
        val first = upload("Device valid", """{"sequence":1,"soilMoistureRaw":100}""")
        val second = upload("Device valid", """{"sequence":1,"soilMoistureRaw":100}""")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(false, json.decodeFromString<DeviceMeasurementResponse>(first.bodyAsText()).duplicate)
        assertEquals(true, json.decodeFromString<DeviceMeasurementResponse>(second.bodyAsText()).duplicate)
    }

    @Test fun `invalid token is unauthorized and disabled device is forbidden`() = testApplication {
        application { configureApplication(config(), { true }, DeviceTokenAuthenticator { token -> if (token == "disabled") DevicePrincipal(id, true) else null }, repository, TelemetryIngestionService(TelemetryStore { _, _ -> error("unused") }, config().telemetry)) }
        val unauthorized = upload("Device invalid", """{"sequence":1,"lightRaw":1}""")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals("no-store", unauthorized.headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", unauthorized.headers[HttpHeaders.Pragma])
        assertEquals("nosniff", unauthorized.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", unauthorized.headers["Referrer-Policy"])
        val disabled = upload("Device disabled", """{"sequence":1,"lightRaw":1}""")
        assertEquals(HttpStatusCode.Forbidden, disabled.status)
        assertEquals("DEVICE_DISABLED", json.decodeFromString<ApiErrorResponse>(disabled.bodyAsText()).code)
    }

    @Test fun `batch endpoint validates size`() = testApplication {
        application { configureApplication(config(), { true }, DeviceTokenAuthenticator { DevicePrincipal(id) }, repository, TelemetryIngestionService(TelemetryStore { _, _ -> error("unused") }, config().telemetry)) }
        val response = upload("Device valid", """{"measurements":[]}""", "/api/device/v1/measurements/batch")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test fun `firmware overflow returns stable 400 without persistence`() = testApplication {
        var storeCalled = false
        val store = TelemetryStore { _, _ -> storeCalled = true; error("must not run") }
        application {
            configureApplication(
                config(),
                { true },
                DeviceTokenAuthenticator { DevicePrincipal(id) },
                repository,
                TelemetryIngestionService(store, config().telemetry),
            )
        }

        val response = upload(
            "Device valid",
            """{"sequence":1,"firmwareVersion":"${"v".repeat(101)}","lightRaw":1}""",
        )
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_FIRMWARE_VERSION", error.code)
        assertEquals(response.headers[HttpHeaders.XRequestId], error.requestId)
        assertEquals(false, storeCalled)
    }

    @Test fun `telemetry rate limit rejects before repeated device authentication`() = testApplication {
        var authenticationAttempts = 0
        val limitedConfig = config(HttpLimitConfig(telemetryRequestsPerPeriod = 1))
        val store = TelemetryStore { _, values ->
            listOf(IngestionResult(MeasurementRecord(1, values.single().measurement), false))
        }
        application {
            configureApplication(
                limitedConfig,
                { true },
                DeviceTokenAuthenticator {
                    authenticationAttempts += 1
                    DevicePrincipal(id)
                },
                repository,
                TelemetryIngestionService(store, limitedConfig.telemetry),
            )
        }

        assertEquals(HttpStatusCode.OK, upload("Device valid", """{"sequence":1,"lightRaw":1}""").status)
        val limited = upload("Device valid", """{"sequence":2,"lightRaw":1}""")

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", json.decodeFromString<ApiErrorResponse>(limited.bodyAsText()).code)
        assertEquals(1, authenticationAttempts)
    }

    @Test fun `per-device ingestion quota returns stable 429 without storing measurements`() = testApplication {
        var storeInvocations = 0
        val store = TelemetryStore { _, _ ->
            storeInvocations += 1
            error("quota must reject before persistence")
        }
        val quota = DeviceIngestionQuota { deviceId, measurementCount ->
            assertEquals(id, deviceId)
            assertEquals(1, measurementCount)
            IngestionQuotaDecision(allowed = false, retryAfterSeconds = 37)
        }
        val configured = config()
        application {
            configureApplication(
                configured,
                { true },
                DeviceTokenAuthenticator { DevicePrincipal(id) },
                repository,
                TelemetryIngestionService(store, configured.telemetry, quota = quota),
            )
        }

        val response = upload("Device valid", """{"sequence":1,"lightRaw":1}""")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("RATE_LIMITED", json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
        assertEquals("37", response.headers[HttpHeaders.RetryAfter])
        assertEquals(0, storeInvocations)
    }

    @Test fun `temporarily quarantined device gets stable forbidden response and retry hint`() = testApplication {
        var storeInvocations = 0
        val configured = config()
        application {
            configureApplication(
                configured,
                { true },
                DeviceTokenAuthenticator {
                    DevicePrincipal(id, quarantinedUntil = Instant.now().plusSeconds(300))
                },
                repository,
                TelemetryIngestionService(
                    TelemetryStore { _, _ -> storeInvocations += 1; error("must not run") },
                    configured.telemetry,
                ),
            )
        }

        val response = upload("Device quarantined", """{"sequence":1,"lightRaw":1}""")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("DEVICE_QUARANTINED", json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
        assertEquals(true, response.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..300)
        assertEquals(0, storeInvocations)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.upload(auth: String, body: String, path: String = "/api/device/v1/measurements") =
        client.post(path) { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, ContentType.Application.Json); setBody(body) }

    private fun config(httpLimits: HttpLimitConfig = HttpLimitConfig()) = AppConfig(
        8080,
        DatabaseConfig("jdbc:none", "x", "x"),
        emptySet(),
        FirebaseConfig("test-project"),
        httpLimits,
    )
}
