package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.config.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import java.net.URI
import com.alad1nks.jaiqal.users.UserApiException

fun Application.configureHttp(config: AppConfig) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
            },
        )
    }

    install(CORS) {
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.XRequestId)

        config.allowedOrigins.forEach { origin ->
            if (origin == "*") {
                anyHost()
            } else {
                val uri = URI(origin)
                require(uri.scheme == "http" || uri.scheme == "https") {
                    "ALLOWED_ORIGINS entries must use http or https"
                }
                require(uri.rawAuthority != null && uri.path.isNullOrEmpty()) {
                    "ALLOWED_ORIGINS entries must be origins without paths"
                }
                allowHost(uri.rawAuthority, schemes = listOf(uri.scheme))
            }
        }
    }

    install(StatusPages) {
        exception<UserApiException> { call, failure ->
            call.respondApiError(HttpStatusCode.fromValue(failure.status), failure.code, failure.message)
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respondApiError(
                status = HttpStatusCode.Unauthorized,
                code = "UNAUTHORIZED",
                message = "Authentication is required",
            )
        }
        exception<JsonConvertException> { call, _ ->
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "INVALID_JSON",
                message = "Request body contains invalid JSON",
            )
        }
        exception<BadRequestException> { call, cause ->
            val invalidJson = cause.causeChain().any { it is JsonConvertException }
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = if (invalidJson) "INVALID_JSON" else "BAD_REQUEST",
                message = if (invalidJson) {
                    "Request body contains invalid JSON"
                } else {
                    "The request is invalid"
                },
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error(
                "Unhandled request failure requestId={} type={}",
                call.callId,
                cause::class.qualifiedName,
            )
            call.respondApiError(
                status = HttpStatusCode.InternalServerError,
                code = "INTERNAL_ERROR",
                message = "An unexpected error occurred",
            )
        }
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }

suspend fun io.ktor.server.application.ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(
        status,
        ApiErrorResponse(
            code = code,
            message = message,
            requestId = callId,
        ),
    )
}
