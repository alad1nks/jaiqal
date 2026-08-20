package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.api.contract.HealthResponse
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.config.HttpLimitConfig
import com.alad1nks.jaiqal.config.DeploymentConfig
import com.alad1nks.jaiqal.config.RuntimeEnvironment
import com.alad1nks.jaiqal.config.TrustedProxyCidr
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTarget
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import com.alad1nks.jaiqal.plugins.DEVICE_TOKEN_AUTH
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApplicationTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun liveHealthReturnsOkAndRequestId() = testApplication {
        application {
            configureApplication(testConfig(), { true })
        }

        val response = client.get("/health/live") {
            header(HttpHeaders.XRequestId, "client-request-123")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("client-request-123", response.headers[HttpHeaders.XRequestId])
        assertNull(response.headers["X-Deployment-Commit"])
        assertNull(response.headers[HttpHeaders.CacheControl])
        assertNull(response.headers["X-Content-Type-Options"])
        assertNull(response.headers["Referrer-Policy"])
        assertEquals(
            HealthResponse(status = "live"),
            json.decodeFromString<HealthResponse>(response.bodyAsText()),
        )

        val readyResponse = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, readyResponse.status)
        assertEquals(
            HealthResponse(status = "ready"),
            json.decodeFromString<HealthResponse>(readyResponse.bodyAsText()),
        )
    }

    @Test
    fun readyHealthReflectsDatabaseAvailability() = testApplication {
        application {
            configureApplication(testConfig(), { false })
        }

        val response = client.get("/health/ready")
        val requestId = assertNotNull(response.headers[HttpHeaders.XRequestId])
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("DATABASE_UNAVAILABLE", error.code)
        assertEquals(requestId, error.requestId)
    }

    @Test
    fun invalidJsonUsesApiErrorContract() = testApplication {
        application {
            configureApplication(testConfig(), { true })
            routing {
                post("/testing/json") {
                    call.receive<HealthResponse>()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        val response = client.post("/testing/json") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"status":""")
        }
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_JSON", error.code)
        assertEquals(response.headers[HttpHeaders.XRequestId], error.requestId)
    }

    @Test
    fun oversizedBodyUsesApiErrorContract() = testApplication {
        application {
            configureApplication(
                testConfig(HttpLimitConfig(maxBodyBytes = 16, telemetryBatchMaxBodyBytes = 32)),
                { true },
            )
            routing {
                post("/testing/json") {
                    call.receive<HealthResponse>()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        val response = client.post("/testing/json") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"status":"body-that-is-too-large"}""")
        }
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("PAYLOAD_TOO_LARGE", error.code)
        assertEquals(response.headers[HttpHeaders.XRequestId], error.requestId)
    }

    @Test
    fun readinessRateLimitUsesApiErrorContract() = testApplication {
        val auditEvents = mutableListOf<SecurityAuditEvent>()
        application {
            configureApplication(
                testConfig(HttpLimitConfig(readinessRequestsPerPeriod = 1)),
                { true },
                securityAuditTrail = SecurityAuditTrail(auditEvents::add),
            )
        }

        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
        val limited = client.get("/health/ready")
        val error = json.decodeFromString<ApiErrorResponse>(limited.bodyAsText())

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", error.code)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        assertEquals(limited.headers[HttpHeaders.XRequestId], error.requestId)
        assertEquals(1, auditEvents.size)
        assertEquals(SecurityAuditAction.RATE_LIMIT, auditEvents.single().action)
        assertEquals(SecurityAuditTarget.READINESS, auditEvents.single().target)
        assertEquals(error.requestId, auditEvents.single().requestId)
    }

    @Test
    fun unhandledFailureUsesApiErrorContract() = testApplication {
        application {
            configureApplication(testConfig(), { true })
            routing {
                get("/testing/failure") {
                    error("Sensitive implementation detail")
                }
            }
        }

        val response = client.get("/testing/failure")
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("INTERNAL_ERROR", error.code)
        assertEquals("An unexpected error occurred", error.message)
        assertEquals(response.headers[HttpHeaders.XRequestId], error.requestId)
    }

    @Test
    fun deviceTokenProviderIsSeparateAndRejectsUnknownTokens() = testApplication {
        val auditEvents = mutableListOf<SecurityAuditEvent>()
        application {
            configureApplication(testConfig(), { true }, securityAuditTrail = SecurityAuditTrail(auditEvents::add))
            routing {
                authenticate(DEVICE_TOKEN_AUTH) {
                    get("/testing/device") {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }

        val response = client.get("/testing/device") {
            header(HttpHeaders.Authorization, "Bearer unknown-device-token")
        }
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHORIZED", error.code)
        assertEquals(response.headers[HttpHeaders.XRequestId], error.requestId)
        assertEquals(SecurityAuditAction.AUTHENTICATION, auditEvents.single().action)
        assertEquals(SecurityAuditTarget.UNKNOWN, auditEvents.single().target)
        assertEquals(error.requestId, auditEvents.single().requestId)
    }

    @Test
    fun productionRejectsPlainHttpAndAddsHstsBehindTrustedHttpsProxy() = testApplication {
        var readinessChecks = 0
        application {
            configureApplication(
                config = productionConfig(),
                databaseReadiness = { readinessChecks += 1; true },
                directPeerAddress = { call ->
                    call.request.headers["X-Test-Direct-Peer"] ?: "203.0.113.10"
                },
            )
        }

        val rejected = client.get("/health/ready") {
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-For", "10.42.7.9")
        }
        val error = json.decodeFromString<ApiErrorResponse>(rejected.bodyAsText())
        assertEquals(HttpStatusCode.UpgradeRequired, rejected.status)
        assertEquals("HTTPS_REQUIRED", error.code)
        assertEquals(0, readinessChecks)

        val accepted = client.get("/health/ready") {
            header("X-Forwarded-Proto", "https")
            header("X-Test-Direct-Peer", "10.42.7.9")
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals(1, readinessChecks)
        assertEquals("max-age=31536000; includeSubDomains", accepted.headers["Strict-Transport-Security"])
        val live = client.get("/health/live") {
            header("X-Forwarded-Proto", "https")
            header("X-Test-Direct-Peer", "10.42.7.9")
        }
        assertEquals(HttpStatusCode.OK, live.status)
        assertEquals(
            "0123456789abcdef0123456789abcdef01234567",
            live.headers["X-Deployment-Commit"],
        )
    }

    private fun testConfig(httpLimits: HttpLimitConfig = HttpLimitConfig()) = AppConfig(
        httpPort = 8080,
        database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/jaiqal",
            user = "test",
            password = "not-logged",
        ),
        allowedOrigins = setOf("https://app.example.test"),
        firebase = FirebaseConfig(projectId = "test-project"),
        httpLimits = httpLimits,
    )

    private fun productionConfig() = AppConfig(
        httpPort = 8080,
        database = DatabaseConfig(
            url = "jdbc:postgresql://db.example.test:5432/jaiqal?sslmode=verify-full&channelBinding=require",
            user = "test",
            password = "not-logged",
        ),
        allowedOrigins = setOf("https://app.example.test"),
        firebase = FirebaseConfig(projectId = "test-project", checkRevokedTokens = true),
        deployment = DeploymentConfig(
            environment = RuntimeEnvironment.PRODUCTION,
            commitSha = "0123456789abcdef0123456789abcdef01234567",
            publicApiUrl = "https://api.example.test",
            trustedProxyTerminatesTls = true,
            trustedProxyCidrs = listOf(TrustedProxyCidr.parse("10.42.0.0/16")),
        ),
    )
}
