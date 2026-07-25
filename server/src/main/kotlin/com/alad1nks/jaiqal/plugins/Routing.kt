package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.HealthResponse
import com.alad1nks.jaiqal.infrastructure.database.DatabaseReadiness
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRouting(databaseReadiness: DatabaseReadiness) {
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
    }
}
