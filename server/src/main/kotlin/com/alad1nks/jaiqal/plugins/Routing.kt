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
import com.alad1nks.jaiqal.telemetry.TelemetryQuotaExceededException
import com.alad1nks.jaiqal.telemetry.DeviceQuarantinedException
import com.alad1nks.jaiqal.users.UserApplicationService
import com.alad1nks.jaiqal.telemetry.MeasurementEventBus
import com.alad1nks.jaiqal.telemetry.PlantTelemetryService
import com.alad1nks.jaiqal.alerts.AlertService
import com.alad1nks.jaiqal.config.HttpLimitConfig
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import io.ktor.server.request.receive
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.plugins.ratelimit.rateLimit
import java.time.Instant

fun Application.configureRouting(
    databaseReadiness: DatabaseReadiness,
    deviceRepository: DeviceRepository? = null,
    telemetry: TelemetryIngestionService? = null,
    userApplication: UserApplicationService? = null,
    plantTelemetry: PlantTelemetryService? = null,
    eventBus: MeasurementEventBus? = null,
    heartbeatSeconds: Long = 15,
    streamMaxLifetimeSeconds: Long = 300,
    streamOwnershipRecheckSeconds: Long = 30,
    deploymentCommitSha: String? = null,
    httpLimits: HttpLimitConfig = HttpLimitConfig(),
    alerts: AlertService? = null,
    securityAuditTrail: SecurityAuditTrail = SecurityAuditTrail.logging(),
) {
    val sseConnectionLimiter = SseConnectionLimiter(
        maxConnectionsPerUser = httpLimits.sseMaxConnectionsPerUser,
        maxConnectionsPerIp = httpLimits.sseMaxConnectionsPerIp,
    )
    routing {
        route("/health") {
            get("/live") {
                deploymentCommitSha?.let { commit -> call.response.header("X-Deployment-Commit", commit) }
                call.respond(HealthResponse(status = "live"))
            }
            rateLimit(READINESS_RATE_LIMIT) {
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
        }
        if (deviceRepository != null && telemetry != null) {
            rateLimit(TELEMETRY_RATE_LIMIT) {
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
        userApplication?.let {
            userApi(
                it,
                plantTelemetry,
                eventBus,
                heartbeatSeconds,
                streamMaxLifetimeSeconds,
                streamOwnershipRecheckSeconds,
                alerts,
                sseConnectionLimiter,
                securityAuditTrail = securityAuditTrail,
            )
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
    val now = Instant.now()
    principal.quarantinedUntil?.takeIf { it.isAfter(now) }?.let { quarantineUntil ->
        response.header(
            HttpHeaders.RetryAfter,
            java.time.Duration.between(now, quarantineUntil).seconds.coerceAtLeast(1).toString(),
        )
        return respondApiError(
            HttpStatusCode.Forbidden,
            "DEVICE_QUARANTINED",
            "Device ingestion is temporarily quarantined",
        )
    }
    val device = devices.findById(principal.deviceId)
        ?: return respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "A valid device token is required")
    try {
        val results = telemetry.ingest(device, requests)
        if (batch) respond(DeviceMeasurementBatchResponse(results)) else respond(results.single())
    } catch (failure: TelemetryValidationException) {
        respondApiError(HttpStatusCode.BadRequest, failure.errorCode, failure.message)
    } catch (failure: TelemetryQuotaExceededException) {
        response.header(HttpHeaders.RetryAfter, failure.retryAfterSeconds.toString())
        respondApiError(
            HttpStatusCode.TooManyRequests,
            "RATE_LIMITED",
            "Too many requests",
        )
    } catch (failure: DeviceQuarantinedException) {
        response.header(HttpHeaders.RetryAfter, failure.retryAfterSeconds.toString())
        respondApiError(
            HttpStatusCode.Forbidden,
            "DEVICE_QUARANTINED",
            "Device ingestion is temporarily quarantined",
        )
    }
}
