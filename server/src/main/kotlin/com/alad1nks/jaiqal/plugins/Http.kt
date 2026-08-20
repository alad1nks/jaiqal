package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditResult
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTarget
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.header
import kotlinx.serialization.json.Json
import java.net.URI
import com.alad1nks.jaiqal.users.UserApiException
import kotlin.time.Duration.Companion.seconds

val READINESS_RATE_LIMIT = RateLimitName("readiness")
val USER_API_RATE_LIMIT = RateLimitName("user-api")
val TELEMETRY_RATE_LIMIT = RateLimitName("telemetry")

fun Application.configureHttp(
    config: AppConfig,
    securityAuditTrail: SecurityAuditTrail = SecurityAuditTrail.logging(),
    directPeerAddress: (ApplicationCall) -> String = { call -> call.request.local.remoteAddress },
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
            },
        )
    }

    install(RequestBodyLimit) {
        bodyLimit { call ->
            if (call.request.path() == "/api/device/v1/measurements/batch") {
                config.httpLimits.telemetryBatchMaxBodyBytes
            } else {
                config.httpLimits.maxBodyBytes
            }
        }
    }

    install(RateLimit) {
        register(READINESS_RATE_LIMIT) {
            requestKey { call -> call.request.local.remoteAddress }
            rateLimiter(
                limit = config.httpLimits.readinessRequestsPerPeriod,
                refillPeriod = config.httpLimits.rateLimitPeriodSeconds.seconds,
            )
        }
        register(USER_API_RATE_LIMIT) {
            requestKey { call -> call.request.local.remoteAddress }
            rateLimiter(
                limit = config.httpLimits.userApiRequestsPerPeriod,
                refillPeriod = config.httpLimits.rateLimitPeriodSeconds.seconds,
            )
        }
        register(TELEMETRY_RATE_LIMIT) {
            requestKey { call -> call.request.local.remoteAddress }
            rateLimiter(
                limit = config.httpLimits.telemetryRequestsPerPeriod,
                refillPeriod = config.httpLimits.rateLimitPeriodSeconds.seconds,
            )
        }
    }

    install(CORS) {
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.RetryAfter)
        exposeHeader("X-RateLimit-Limit")
        exposeHeader("X-RateLimit-Remaining")
        exposeHeader("X-RateLimit-Reset")

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
            securityAuditTrail.record(
                SecurityAuditEvent(
                    action = SecurityAuditAction.AUTHENTICATION,
                    result = SecurityAuditResult.REJECTED,
                    target = call.securityAuditTarget(),
                    requestId = call.callId,
                ),
            )
            call.respondApiError(
                status = HttpStatusCode.Unauthorized,
                code = "UNAUTHORIZED",
                message = "Authentication is required",
            )
        }
        status(HttpStatusCode.PayloadTooLarge) { call, _ ->
            call.respondApiError(
                status = HttpStatusCode.PayloadTooLarge,
                code = "PAYLOAD_TOO_LARGE",
                message = "Request body is too large",
            )
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            securityAuditTrail.record(
                SecurityAuditEvent(
                    action = SecurityAuditAction.RATE_LIMIT,
                    result = SecurityAuditResult.REJECTED,
                    target = call.securityAuditTarget(),
                    requestId = call.callId,
                ),
            )
            call.respondApiError(
                status = HttpStatusCode.TooManyRequests,
                code = "RATE_LIMITED",
                message = "Too many requests",
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
        exception<PayloadTooLargeException> { call, _ ->
            call.respondApiError(
                status = HttpStatusCode.PayloadTooLarge,
                code = "PAYLOAD_TOO_LARGE",
                message = "Request body is too large",
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

    intercept(ApplicationCallPipeline.Plugins) {
        if (context.request.path().isSensitiveApiPath()) {
            context.response.header(HttpHeaders.CacheControl, "no-store")
            context.response.header(HttpHeaders.Pragma, "no-cache")
            context.response.header("X-Content-Type-Options", "nosniff")
            context.response.header("Referrer-Policy", "no-referrer")
        }
    }

    if (config.deployment.isProduction) {
        intercept(ApplicationCallPipeline.Plugins) {
            context.response.header(
                "Strict-Transport-Security",
                "max-age=31536000; includeSubDomains",
            )
            val directHttps = context.request.local.scheme.equals("https", ignoreCase = true)
            val trustedProxyHttps = config.deployment.trustedProxyTerminatesTls &&
                config.deployment.isTrustedProxyPeer(directPeerAddress(context)) &&
                context.request.headers["X-Forwarded-Proto"].equals("https", ignoreCase = true)
            if (!directHttps && !trustedProxyHttps) {
                context.respondApiError(
                    status = HttpStatusCode.UpgradeRequired,
                    code = "HTTPS_REQUIRED",
                    message = "HTTPS is required",
                )
                finish()
            }
        }
    }
}

private fun String.isSensitiveApiPath(): Boolean =
    this == "/api/v1" ||
        startsWith("/api/v1/") ||
        this == "/api/device" ||
        startsWith("/api/device/")

private fun io.ktor.server.application.ApplicationCall.securityAuditTarget(): SecurityAuditTarget =
    when {
        request.path().startsWith("/api/device/") -> SecurityAuditTarget.DEVICE_API
        request.path().startsWith("/api/v1/") -> SecurityAuditTarget.USER_API
        request.path() == "/health/ready" -> SecurityAuditTarget.READINESS
        else -> SecurityAuditTarget.UNKNOWN
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
