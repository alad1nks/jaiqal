package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.api.contract.DeviceMeasurementResponse
import com.alad1nks.jaiqal.auth.DevicePrincipal
import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.telemetry.IngestionResult
import com.alad1nks.jaiqal.telemetry.MeasurementRecord
import com.alad1nks.jaiqal.telemetry.TelemetryIngestionService
import com.alad1nks.jaiqal.telemetry.TelemetryStore
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
        assertEquals(HttpStatusCode.Unauthorized, upload("Device invalid", """{"sequence":1,"lightRaw":1}""").status)
        val disabled = upload("Device disabled", """{"sequence":1,"lightRaw":1}""")
        assertEquals(HttpStatusCode.Forbidden, disabled.status)
        assertEquals("DEVICE_DISABLED", json.decodeFromString<ApiErrorResponse>(disabled.bodyAsText()).code)
    }

    @Test fun `batch endpoint validates size`() = testApplication {
        application { configureApplication(config(), { true }, DeviceTokenAuthenticator { DevicePrincipal(id) }, repository, TelemetryIngestionService(TelemetryStore { _, _ -> error("unused") }, config().telemetry)) }
        val response = upload("Device valid", """{"measurements":[]}""", "/api/device/v1/measurements/batch")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.upload(auth: String, body: String, path: String = "/api/device/v1/measurements") =
        client.post(path) { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, ContentType.Application.Json); setBody(body) }

    private fun config() = AppConfig(8080, DatabaseConfig("jdbc:none", "x", "x"), emptySet(), FirebaseConfig("test-project"))
}
