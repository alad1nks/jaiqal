package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.api.contract.HealthResponse
import com.alad1nks.jaiqal.auth.DevicePrincipal
import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.config.HttpLimitConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import com.alad1nks.jaiqal.plugins.FIREBASE_USER_AUTH
import com.alad1nks.jaiqal.plugins.SseConnectionLimiter
import com.alad1nks.jaiqal.plugins.USER_API_RATE_LIMIT
import com.alad1nks.jaiqal.telemetry.IngestionResult
import com.alad1nks.jaiqal.telemetry.MeasurementRecord
import com.alad1nks.jaiqal.telemetry.TelemetryIngestionService
import com.alad1nks.jaiqal.telemetry.TelemetryStore
import com.alad1nks.jaiqal.users.FirebaseUserIdentityService
import com.alad1nks.jaiqal.users.UserIdentityRecord
import com.alad1nks.jaiqal.users.UserIdentityStore
import com.alad1nks.jaiqal.users.UserRecord
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbuseLoadTest {
    private val json = Json

    @Test
    fun `invalid Firebase token flood is rejected before repeated verification`() = testApplication {
        val verificationAttempts = AtomicInteger()
        val limit = 8
        application {
            configureApplication(
                config(HttpLimitConfig(userApiRequestsPerPeriod = limit)),
                databaseReadiness = { true },
                firebaseTokenVerifier = FirebaseTokenVerifier {
                    verificationAttempts.incrementAndGet()
                    throw FirebaseTokenVerificationException()
                },
                firebaseUsers = unusedFirebaseUsers(),
                securityAuditTrail = noOpAuditTrail,
            )
            routing {
                rateLimit(USER_API_RATE_LIMIT) {
                    authenticate(FIREBASE_USER_AUTH) {
                        get("/testing/authenticated") { call.respond(HttpStatusCode.NoContent) }
                    }
                }
            }
        }

        val responses = concurrentRequests(64) { index ->
            client.get("/testing/authenticated") { bearerAuth("invalid-$index") }
        }

        responses.assertFloodResult(HttpStatusCode.Unauthorized, limit)
        assertEquals(limit, verificationAttempts.get())
    }

    @Test
    fun `readiness flood is bounded before database checks and service stays live`() = testApplication {
        val readinessChecks = AtomicInteger()
        val checksInFlight = AtomicInteger()
        val peakChecksInFlight = AtomicInteger()
        val limit = 8
        application {
            configureApplication(
                config(HttpLimitConfig(readinessRequestsPerPeriod = limit)),
                databaseReadiness = {
                    readinessChecks.incrementAndGet()
                    val current = checksInFlight.incrementAndGet()
                    peakChecksInFlight.accumulateAndGet(current, ::maxOf)
                    try {
                        delay(25)
                        true
                    } finally {
                        checksInFlight.decrementAndGet()
                    }
                },
                securityAuditTrail = noOpAuditTrail,
            )
        }

        val responses = concurrentRequests(64) { client.get("/health/ready") }

        responses.assertFloodResult(HttpStatusCode.OK, limit)
        assertEquals(1, readinessChecks.get())
        assertEquals(1, peakChecksInFlight.get())
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `oversized JSON flood is rejected before decoding or route work`() = testApplication {
        val handledRequests = AtomicInteger()
        application {
            configureApplication(
                config(HttpLimitConfig(maxBodyBytes = 64, telemetryBatchMaxBodyBytes = 128)),
                databaseReadiness = { true },
                securityAuditTrail = noOpAuditTrail,
            )
            routing {
                post("/testing/json") {
                    call.receive<HealthResponse>()
                    handledRequests.incrementAndGet()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
        val oversizedJson = """{"status":"${"x".repeat(4_096)}"}"""

        val responses = concurrentRequests(48) {
            client.post("/testing/json") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(oversizedJson)
            }
        }

        assertEquals(setOf(HttpStatusCode.PayloadTooLarge), responses.map(HttpResponse::status).toSet())
        assertEquals(0, handledRequests.get())
        val error = json.decodeFromString<ApiErrorResponse>(responses.first().bodyAsText())
        assertEquals("PAYLOAD_TOO_LARGE", error.code)
    }

    @Test
    fun `telemetry burst is bounded before device authentication and persistence`() = testApplication {
        val deviceId = UUID.randomUUID()
        val device = DeviceRecord(deviceId, null, "load sensor", "hash", createdAt = OffsetDateTime.now())
        val authenticationAttempts = AtomicInteger()
        val persistenceCalls = AtomicInteger()
        val measurementIds = AtomicLong()
        val limit = 12
        val configured = config(HttpLimitConfig(telemetryRequestsPerPeriod = limit))
        val devices = object : DeviceRepository {
            override fun create(device: DeviceRecord) = device
            override fun findById(id: UUID) = device.takeIf { it.id == id }
            override fun findByPlantId(plantId: UUID) = emptyList<DeviceRecord>()
        }
        val store = TelemetryStore { _, measurements ->
            persistenceCalls.incrementAndGet()
            measurements.map { IngestionResult(MeasurementRecord(measurementIds.incrementAndGet(), it.measurement), false) }
        }
        application {
            configureApplication(
                configured,
                databaseReadiness = { true },
                deviceTokenAuthenticator = DeviceTokenAuthenticator {
                    authenticationAttempts.incrementAndGet()
                    DevicePrincipal(deviceId)
                },
                deviceRepository = devices,
                telemetry = TelemetryIngestionService(store, configured.telemetry),
                securityAuditTrail = noOpAuditTrail,
            )
        }

        val responses = concurrentRequests(80) { sequence ->
            client.post("/api/device/v1/measurements") {
                header(HttpHeaders.Authorization, "Device valid")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"sequence":$sequence,"lightRaw":1}""")
            }
        }

        responses.assertFloodResult(HttpStatusCode.OK, limit)
        assertEquals(limit, authenticationAttempts.get())
        assertEquals(limit, persistenceCalls.get())
    }

    @Test
    fun `concurrent SSE fan-out never exceeds user or peer caps and releases leases`() = runBlocking {
        val byUser = SseConnectionLimiter(maxConnectionsPerUser = 4, maxConnectionsPerIp = 128)
        val oneUser = UUID.randomUUID()
        assertEquals(
            4,
            heldFanOut(byUser, 128, user = { oneUser }, address = { "192.0.2.$it" }),
        )
        assertNotNull(byUser.tryAcquire(oneUser, "192.0.2.250")).close()

        val byPeer = SseConnectionLimiter(maxConnectionsPerUser = 128, maxConnectionsPerIp = 6)
        assertEquals(
            6,
            heldFanOut(byPeer, 128, user = { UUID.randomUUID() }, address = { "198.51.100.10" }),
        )
        assertNotNull(byPeer.tryAcquire(UUID.randomUUID(), "198.51.100.10")).close()
    }

    private fun config(httpLimits: HttpLimitConfig) = AppConfig(
        httpPort = 8080,
        database = DatabaseConfig("jdbc:none", "test", "not-logged"),
        allowedOrigins = emptySet(),
        firebase = FirebaseConfig("test-project"),
        httpLimits = httpLimits,
    )

    private val noOpAuditTrail = SecurityAuditTrail { }

    private fun unusedFirebaseUsers() = FirebaseUserIdentityService(
        store = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String): UserRecord = error("unused")
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord = error("unused")
        },
        autoProvisionUsers = false,
    )

    private suspend fun List<HttpResponse>.assertFloodResult(allowedStatus: HttpStatusCode, limit: Int) {
        assertEquals(limit, count { it.status == allowedStatus })
        assertEquals(size - limit, count { it.status == HttpStatusCode.TooManyRequests })
        assertTrue(none { it.status == HttpStatusCode.InternalServerError })
        val limited = first { it.status == HttpStatusCode.TooManyRequests }
        val error = json.decodeFromString<ApiErrorResponse>(limited.bodyAsText())
        assertEquals("RATE_LIMITED", error.code)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

    private suspend fun <T> concurrentRequests(count: Int, request: suspend (Int) -> T): List<T> =
        coroutineScope {
            (0 until count).map { index -> async { request(index) } }.awaitAll()
        }

    private suspend fun heldFanOut(
        limiter: SseConnectionLimiter,
        count: Int,
        user: (Int) -> UUID,
        address: (Int) -> String,
    ): Int = coroutineScope {
        val start = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val accepted = AtomicInteger()
        val jobs = (0 until count).map { index ->
            async(Dispatchers.Default) {
                start.await()
                val lease = limiter.tryAcquire(user(index), address(index))
                if (lease != null) accepted.incrementAndGet()
                attempts.incrementAndGet()
                if (lease != null) {
                    release.await()
                    lease.close()
                }
            }
        }

        start.complete(Unit)
        withTimeout(5_000) {
            while (attempts.get() != count) yield()
        }
        val observed = accepted.get()
        release.complete(Unit)
        jobs.awaitAll()
        observed
    }
}
