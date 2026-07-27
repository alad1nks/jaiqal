package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.auth.UserPrincipal
import com.alad1nks.jaiqal.users.UserApplicationService
import com.alad1nks.jaiqal.telemetry.MeasurementEventBus
import com.alad1nks.jaiqal.telemetry.PlantTelemetryService
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import com.alad1nks.jaiqal.api.contract.PlantTelemetryUpdate
import com.alad1nks.jaiqal.alerts.AlertService

fun Route.userApi(service: UserApplicationService, plantTelemetry: PlantTelemetryService? = null, eventBus: MeasurementEventBus? = null, heartbeatSeconds: Long = 15, alerts: AlertService? = null) {
    route("/api/v1") {
        route("/auth") {
            post("/register") { call.legacyAuthGone() }
            post("/login") { call.legacyAuthGone() }
            post("/refresh") { call.legacyAuthGone() }
            post("/logout") { call.legacyAuthGone() }
        }
        authenticate(FIREBASE_USER_AUTH) {
            route("/plants") {
                get { call.respond(service.listPlants(call.userId())) }
                post { call.respond(HttpStatusCode.Created, service.createPlant(call.userId(), call.receive<CreatePlantRequest>())) }
                route("/{plantId}") {
                    if (alerts != null) {
                        route("/alert-rules") {
                            get { call.respond(alerts.rules(call.userId(), call.uuidParameter("plantId"))) }
                            put { call.respond(alerts.putRules(call.userId(), call.uuidParameter("plantId"), call.receive<PutAlertRulesRequest>())) }
                        }
                        route("/alerts") {
                            get { call.respond(alerts.alerts(call.userId(), call.uuidParameter("plantId"))) }
                            post("/{alertId}/acknowledge") { call.respond(alerts.acknowledge(call.userId(), call.uuidParameter("plantId"), call.uuidParameter("alertId"))) }
                        }
                    }
                    get { call.respond(service.getPlant(call.userId(), call.uuidParameter("plantId"))) }
                    if (plantTelemetry != null) {
                        get("/latest") { call.respond(plantTelemetry.latest(call.userId(), call.uuidParameter("plantId"))) }
                        get("/history") {
                            call.respond(plantTelemetry.history(call.userId(), call.uuidParameter("plantId"), call.request.queryParameters["from"], call.request.queryParameters["to"], call.request.queryParameters["interval"]))
                        }
                        if (eventBus != null) get("/stream") {
                            val userId = call.userId()
                            val plantId = call.uuidParameter("plantId")
                            plantTelemetry.requireOwnership(userId, plantId)
                            call.respondTextWriter(ContentType.Text.EventStream) {
                                write(": connected\n\n"); flush()
                                while (true) {
                                    val event = withTimeoutOrNull(heartbeatSeconds * 1_000) {
                                        eventBus.updates.first { it.plantId == plantId }
                                    }
                                    if (event == null) write(": heartbeat\n\n") else {
                                        val update = PlantTelemetryUpdate(plantId.toString(), event.measurement.measurement.deviceId.toString(), event.measurement.id)
                                        write("event: measurement\ndata: ${Json.encodeToString(update)}\n\n")
                                    }
                                    flush()
                                }
                            }
                        }
                    }
                    patch { call.respond(service.updatePlant(call.userId(), call.uuidParameter("plantId"), call.receive<UpdatePlantRequest>())) }
                    delete { service.archivePlant(call.userId(), call.uuidParameter("plantId")); call.respondText("", status = HttpStatusCode.NoContent) }
                }
            }
            route("/devices") {
                post("/claim") { call.respond(service.claimDevice(call.userId(), call.receive<ClaimDeviceRequest>())) }
                get { call.respond(service.listDevices(call.userId())) }
                route("/{deviceId}") {
                    get { call.respond(service.getDevice(call.userId(), call.uuidParameter("deviceId"))) }
                    patch { call.respond(service.updateDevice(call.userId(), call.uuidParameter("deviceId"), call.receive<UpdateDeviceRequest>())) }
                    patch("/calibration") { call.respond(service.updateCalibration(call.userId(), call.uuidParameter("deviceId"), call.receive<UpdateCalibrationRequest>())) }
                    post("/rotate-token") { call.respond(service.rotateDeviceToken(call.userId(), call.uuidParameter("deviceId"))) }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.legacyAuthGone() =
    respondApiError(
        status = HttpStatusCode.Gone,
        code = "LEGACY_AUTH_DISABLED",
        message = "Password authentication is no longer available; use Firebase Authentication",
    )

internal fun io.ktor.server.application.ApplicationCall.userId(): UUID =
    principal<UserPrincipal>()?.userId
        ?: throw com.alad1nks.jaiqal.users.UserApiException(401, "UNAUTHORIZED", "A valid user token is required")

private fun io.ktor.server.application.ApplicationCall.uuidParameter(name: String): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw com.alad1nks.jaiqal.users.UserApiException(400, "INVALID_ID", "Identifier is invalid")
