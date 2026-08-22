package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.auth.UserPrincipal
import com.alad1nks.jaiqal.users.UserApplicationService
import com.alad1nks.jaiqal.users.AccountDeletionService
import com.alad1nks.jaiqal.telemetry.MeasurementEventBus
import com.alad1nks.jaiqal.telemetry.PlantTelemetryService
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import com.alad1nks.jaiqal.api.contract.PlantTelemetryUpdate
import com.alad1nks.jaiqal.alerts.AlertService
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditResult
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTarget
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail

fun Route.userApi(
    service: UserApplicationService,
    plantTelemetry: PlantTelemetryService? = null,
    eventBus: MeasurementEventBus? = null,
    heartbeatSeconds: Long = 15,
    streamMaxLifetimeSeconds: Long = 300,
    streamOwnershipRecheckSeconds: Long = 30,
    alerts: AlertService? = null,
    sseConnectionLimiter: SseConnectionLimiter = SseConnectionLimiter(),
    clock: Clock = Clock.systemUTC(),
    securityAuditTrail: SecurityAuditTrail = SecurityAuditTrail.logging(),
    accountDeletion: AccountDeletionService? = null,
) {
    route("/api/v1") {
        rateLimit(USER_API_RATE_LIMIT) {
            route("/auth") {
                post("/register") { call.legacyAuthGone() }
                post("/login") { call.legacyAuthGone() }
                post("/refresh") { call.legacyAuthGone() }
                post("/logout") { call.legacyAuthGone() }
            }
            authenticate(FIREBASE_USER_AUTH) {
                get("/auth/me") {
                    val principal = call.userPrincipal()
                    call.respond(
                        CurrentUserResponse(
                            id = principal.userId.toString(),
                            email = principal.email,
                            emailVerified = principal.emailVerified,
                        ),
                    )
                }
                if (accountDeletion != null) {
                    delete("/auth/me") {
                        val principal = call.userPrincipal(allowDeleted = true)
                        call.respond(
                            call.auditedMutation(
                                securityAuditTrail,
                                principal.userId,
                                SecurityAuditAction.DELETE_ACCOUNT,
                                SecurityAuditTarget.USER_API,
                                principal.userId,
                            ) {
                                if (!principal.deleted) {
                                    accountDeletion.deleteAccount(principal.userId, principal.firebaseUid)
                                }
                                DeleteAccountResponse()
                            },
                        )
                    }
                }
                route("/plants") {
                    get { call.respond(service.listPlants(call.userId())) }
                    post { call.respond(HttpStatusCode.Created, service.createPlant(call.userId(), call.receive<CreatePlantRequest>())) }
                    route("/{plantId}") {
                        if (alerts != null) {
                            route("/alert-rules") {
                                get { call.respond(alerts.rules(call.userId(), call.uuidParameter("plantId"))) }
                                put {
                                    val userId = call.userId()
                                    val plantId = call.uuidParameter("plantId")
                                    call.respond(call.auditedMutation(securityAuditTrail, userId, SecurityAuditAction.UPDATE_ALERT_RULES, SecurityAuditTarget.PLANT, plantId) {
                                        alerts.putRules(userId, plantId, call.receive<PutAlertRulesRequest>())
                                    })
                                }
                            }
                            route("/alerts") {
                                get { call.respond(alerts.alerts(call.userId(), call.uuidParameter("plantId"))) }
                                post("/{alertId}/acknowledge") {
                                    val userId = call.userId()
                                    val plantId = call.uuidParameter("plantId")
                                    val alertId = call.uuidParameter("alertId")
                                    call.respond(call.auditedMutation(securityAuditTrail, userId, SecurityAuditAction.ACKNOWLEDGE_ALERT, SecurityAuditTarget.ALERT, alertId) {
                                        alerts.acknowledge(userId, plantId, alertId)
                                    })
                                }
                            }
                        }
                        get { call.respond(service.getPlant(call.userId(), call.uuidParameter("plantId"))) }
                        if (plantTelemetry != null) {
                            get("/latest") { call.respond(plantTelemetry.latest(call.userId(), call.uuidParameter("plantId"))) }
                            get("/history") {
                                call.respond(plantTelemetry.history(call.userId(), call.uuidParameter("plantId"), call.request.queryParameters["from"], call.request.queryParameters["to"], call.request.queryParameters["interval"]))
                            }
                            if (eventBus != null) get("/stream") {
                                val principal = call.userPrincipal()
                                val userId = principal.userId
                                val plantId = call.uuidParameter("plantId")
                                plantTelemetry.requireOwnership(userId, plantId)
                                val lease = sseConnectionLimiter.tryAcquire(userId, call.request.local.remoteAddress)
                                if (lease == null) {
                                    call.response.header(HttpHeaders.RetryAfter, "30")
                                    call.respondApiError(
                                        status = HttpStatusCode.TooManyRequests,
                                        code = "SSE_CONNECTION_LIMIT_REACHED",
                                        message = "Too many active telemetry streams",
                                    )
                                } else {
                                    try {
                                        call.respondTextWriter(ContentType.Text.EventStream) {
                                            withTimeoutOrNull(streamMaxLifetimeSeconds * 1_000) {
                                                val startedAt = Instant.now(clock)
                                                val deadline = minOf(
                                                    principal.expiresAt,
                                                    startedAt.plusSeconds(streamMaxLifetimeSeconds),
                                                )
                                                if (!startedAt.isBefore(deadline)) return@withTimeoutOrNull

                                                var nextHeartbeatAt = startedAt.plusSeconds(heartbeatSeconds)
                                                var nextOwnershipCheckAt = startedAt.plusSeconds(streamOwnershipRecheckSeconds)
                                                write(": connected\n\n")
                                                flush()
                                                while (Instant.now(clock).isBefore(deadline)) {
                                                    val beforeWait = Instant.now(clock)
                                                    val waitUntil = minOf(deadline, nextHeartbeatAt, nextOwnershipCheckAt)
                                                    val waitMillis = Duration.between(beforeWait, waitUntil)
                                                        .toMillis()
                                                        .coerceAtLeast(1)
                                                    val event = withTimeoutOrNull(waitMillis) {
                                                        eventBus.updates.first { it.plantId == plantId }
                                                    }
                                                    val now = Instant.now(clock)
                                                    if (!now.isBefore(deadline)) break
                                                    if (!now.isBefore(nextOwnershipCheckAt)) {
                                                        if (!plantTelemetry.ownsPlant(userId, plantId)) break
                                                        nextOwnershipCheckAt = now.plusSeconds(streamOwnershipRecheckSeconds)
                                                    }
                                                    if (event != null) {
                                                        val update = PlantTelemetryUpdate(plantId.toString(), event.measurement.measurement.deviceId.toString(), event.measurement.id)
                                                        write("event: measurement\ndata: ${Json.encodeToString(update)}\n\n")
                                                        nextHeartbeatAt = now.plusSeconds(heartbeatSeconds)
                                                    } else if (!now.isBefore(nextHeartbeatAt)) {
                                                        write(": heartbeat\n\n")
                                                        nextHeartbeatAt = now.plusSeconds(heartbeatSeconds)
                                                    }
                                                    flush()
                                                }
                                            }
                                        }
                                    } finally {
                                        lease.close()
                                    }
                                }
                            }
                        }
                        patch { call.respond(service.updatePlant(call.userId(), call.uuidParameter("plantId"), call.receive<UpdatePlantRequest>())) }
                        delete { service.archivePlant(call.userId(), call.uuidParameter("plantId")); call.respondText("", status = HttpStatusCode.NoContent) }
                    }
                }
                route("/devices") {
                    post("/claim") {
                        val userId = call.userId()
                        call.respond(call.auditedMutation(securityAuditTrail, userId, SecurityAuditAction.CLAIM_DEVICE, SecurityAuditTarget.DEVICE, resourceId = null, successResourceId = { UUID.fromString(it.id) }) {
                            service.claimDevice(userId, call.receive<ClaimDeviceRequest>())
                        })
                    }
                    get { call.respond(service.listDevices(call.userId())) }
                    route("/{deviceId}") {
                        get { call.respond(service.getDevice(call.userId(), call.uuidParameter("deviceId"))) }
                        patch { call.respond(service.updateDevice(call.userId(), call.uuidParameter("deviceId"), call.receive<UpdateDeviceRequest>())) }
                        patch("/calibration") {
                            val userId = call.userId()
                            val deviceId = call.uuidParameter("deviceId")
                            call.respond(call.auditedMutation(securityAuditTrail, userId, SecurityAuditAction.UPDATE_DEVICE_CALIBRATION, SecurityAuditTarget.DEVICE, deviceId) {
                                service.updateCalibration(userId, deviceId, call.receive<UpdateCalibrationRequest>())
                            })
                        }
                        post("/rotate-token") {
                            val userId = call.userId()
                            val deviceId = call.uuidParameter("deviceId")
                            call.respond(call.auditedMutation(securityAuditTrail, userId, SecurityAuditAction.ROTATE_DEVICE_TOKEN, SecurityAuditTarget.DEVICE, deviceId) {
                                service.rotateDeviceToken(userId, deviceId)
                            })
                        }
                        post("/restore") {
                            val userId = call.userId()
                            val deviceId = call.uuidParameter("deviceId")
                            call.respond(call.auditedMutation(securityAuditTrail, userId, SecurityAuditAction.RESTORE_DEVICE, SecurityAuditTarget.DEVICE, deviceId) {
                                service.restoreDevice(userId, deviceId)
                            })
                        }
                    }
                }
            }
        }
    }
}

private suspend fun <T> io.ktor.server.application.ApplicationCall.auditedMutation(
    auditTrail: SecurityAuditTrail,
    actorUserId: UUID,
    action: SecurityAuditAction,
    target: SecurityAuditTarget,
    resourceId: UUID?,
    successResourceId: (T) -> UUID? = { resourceId },
    operation: suspend () -> T,
): T = try {
    operation().also {
        auditTrail.record(SecurityAuditEvent(action, SecurityAuditResult.SUCCESS, target, actorUserId, successResourceId(it), callId))
    }
} catch (failure: Throwable) {
    auditTrail.record(
        SecurityAuditEvent(
            action,
            if (failure is com.alad1nks.jaiqal.users.UserApiException) SecurityAuditResult.REJECTED else SecurityAuditResult.FAILURE,
            target,
            actorUserId,
            resourceId,
            callId,
        ),
    )
    throw failure
}

private suspend fun io.ktor.server.application.ApplicationCall.legacyAuthGone() =
    respondApiError(
        status = HttpStatusCode.Gone,
        code = "LEGACY_AUTH_DISABLED",
        message = "Password authentication is no longer available; use Firebase Authentication",
    )

internal fun io.ktor.server.application.ApplicationCall.userPrincipal(
    allowDeleted: Boolean = false,
): UserPrincipal = principal<UserPrincipal>()
    ?.takeIf { allowDeleted || !it.deleted }
    ?: throw com.alad1nks.jaiqal.users.UserApiException(401, "UNAUTHORIZED", "A valid user token is required")

internal fun io.ktor.server.application.ApplicationCall.userId(): UUID = userPrincipal().userId

private fun io.ktor.server.application.ApplicationCall.uuidParameter(name: String): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw com.alad1nks.jaiqal.users.UserApiException(400, "INVALID_ID", "Identifier is invalid")
