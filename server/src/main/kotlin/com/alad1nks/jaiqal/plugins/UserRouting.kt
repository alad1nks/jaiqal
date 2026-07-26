package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.users.UserApplicationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.util.UUID

fun Route.userApi(service: UserApplicationService) {
    route("/api/v1") {
        route("/auth") {
            post("/register") { call.respond(HttpStatusCode.Created, service.register(call.receive<RegisterRequest>())) }
            post("/login") { call.respond(service.login(call.receive<LoginRequest>())) }
            post("/refresh") { call.respond(service.refresh(call.receive<RefreshRequest>())) }
        }
        authenticate(USER_JWT_AUTH) {
            post("/auth/logout") {
                service.logout(call.userId(), call.receive<LogoutRequest>())
                call.respondText("", status = HttpStatusCode.NoContent)
            }
            route("/plants") {
                get { call.respond(service.listPlants(call.userId())) }
                post { call.respond(HttpStatusCode.Created, service.createPlant(call.userId(), call.receive<CreatePlantRequest>())) }
                route("/{plantId}") {
                    get { call.respond(service.getPlant(call.userId(), call.uuidParameter("plantId"))) }
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

private fun io.ktor.server.application.ApplicationCall.userId(): UUID =
    principal<JWTPrincipal>()?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw com.alad1nks.jaiqal.users.UserApiException(401, "UNAUTHORIZED", "A valid user token is required")

private fun io.ktor.server.application.ApplicationCall.uuidParameter(name: String): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw com.alad1nks.jaiqal.users.UserApiException(400, "INVALID_ID", "Identifier is invalid")
