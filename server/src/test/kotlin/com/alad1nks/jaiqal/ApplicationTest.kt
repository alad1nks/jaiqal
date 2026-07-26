package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.api.contract.HealthResponse
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.plugins.DEVICE_TOKEN_AUTH
import com.alad1nks.jaiqal.plugins.FIREBASE_USER_AUTH
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
    fun firebaseProviderRejectsMissingTokenWithApiError() = testApplication {
        application {
            configureApplication(testConfig(), { true })
            routing {
                authenticate(FIREBASE_USER_AUTH) {
                    get("/testing/user") {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }

        val response = client.get("/testing/user")
        val error = json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHORIZED", error.code)
        assertEquals(response.headers[HttpHeaders.XRequestId], error.requestId)

        val empty = client.get("/testing/user") { header(HttpHeaders.Authorization, "Bearer ") }
        assertEquals(HttpStatusCode.Unauthorized, empty.status)
    }

    @Test
    fun deviceTokenProviderIsSeparateAndRejectsUnknownTokens() = testApplication {
        application {
            configureApplication(testConfig(), { true })
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
    }

    private fun testConfig() = AppConfig(
        httpPort = 8080,
        database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/jaiqal",
            user = "test",
            password = "not-logged",
        ),
        firebase = FirebaseConfig("test-project"),
        allowedOrigins = setOf("https://app.example.test"),
    )
}
