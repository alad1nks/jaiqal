package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.HealthResponse
import com.alad1nks.jaiqal.infrastructure.database.DatabaseReadiness
import com.alad1nks.jaiqal.api.contract.DeviceMeasurementBatchRequest
import com.alad1nks.jaiqal.api.contract.DeviceMeasurementBatchResponse
import com.alad1nks.jaiqal.api.contract.DeviceMeasurementRequest
import com.alad1nks.jaiqal.auth.DevicePrincipal
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.telemetry.TelemetryIngestionService
import com.alad1nks.jaiqal.telemetry.TelemetryValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRouting(
    databaseReadiness: DatabaseReadiness,
    deviceRepository: DeviceRepository? = null,
    telemetry: TelemetryIngestionService? = null,
) {
    routing {
        route("/health") {
            get("/live") {
                call.respond(HealthResponse(status = "live"))
            }
            get("/ready") {
                if (databaseReadiness.isReady()) {
                    call.respond(HealthResponse(status = "ready"))
                } else {
                    call.respondApiError(
                        status = HttpStatusCode.ServiceUnavailable,
                        code = "DATABASE_UNAVAILABLE",
                        message = "Database is unavailable",
                    )
                }
            }
        }
        if (deviceRepository != null && telemetry != null) {
            authenticate(DEVICE_TOKEN_AUTH) {
                route("/api/device/v1/measurements") {
                    post {
                        call.ingest(deviceRepository, telemetry, listOf(call.receive<DeviceMeasurementRequest>()), false)
                    }
                    post("/batch") {
                        val request = call.receive<DeviceMeasurementBatchRequest>()
                        call.ingest(deviceRepository, telemetry, request.measurements, true)
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.ingest(
    devices: DeviceRepository,
    telemetry: TelemetryIngestionService,
    requests: List<DeviceMeasurementRequest>,
    batch: Boolean,
) {
    val principal = principal<DevicePrincipal>() ?: return respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "A valid device token is required")
    if (principal.disabled) return respondApiError(HttpStatusCode.Forbidden, "DEVICE_DISABLED", "The device is disabled")
    val device = devices.findById(principal.deviceId)
        ?: return respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "A valid device token is required")
    try {
        val results = telemetry.ingest(device, requests)
        if (batch) respond(DeviceMeasurementBatchResponse(results)) else respond(results.single())
    } catch (failure: TelemetryValidationException) {
        respondApiError(HttpStatusCode.BadRequest, failure.errorCode, failure.message)
    }
}
