package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.auth.FakeFirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.plants.PlantRecord
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import com.alad1nks.jaiqal.configureApplication
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.config.HttpLimitConfig
import com.alad1nks.jaiqal.config.HistoryConfig
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditResult
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTarget
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import com.alad1nks.jaiqal.telemetry.HistoryRequest
import com.alad1nks.jaiqal.telemetry.MeasurementEventBus
import com.alad1nks.jaiqal.telemetry.PlantTelemetryRepository
import com.alad1nks.jaiqal.telemetry.PlantTelemetryService
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger

class UserApplicationTest {
    @Test fun `account deletion route uses internal identity and emits credential-free audit`() = testApplication {
        val store = MemoryStore()
        val owner = UserRecord(UUID.randomUUID(), "owner@example.test", null, OffsetDateTime.now(clock))
        val events = mutableListOf<SecurityAuditEvent>()
        val deleted = mutableListOf<Pair<UUID, String>>()
        var tombstoned = false
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("firebase-id-token" to Result.success(
                VerifiedFirebaseToken("sensitive-firebase-uid", owner.email, true, validUntil),
            )),
        )
        val identities = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String) =
                owner.takeUnless { tombstoned }
            override fun deletedIdentityOwner(provider: String, externalSubject: String) =
                owner.id.takeIf { tombstoned }
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord) = error("unused")
        }
        application {
            configureApplication(
                AppConfig(8080, DatabaseConfig("jdbc:none", "x", "x"), emptySet(), FirebaseConfig("test-project")),
                { true },
                userApplication = UserApplicationService(store, clock = clock),
                firebaseTokenVerifier = verifier,
                firebaseUsers = FirebaseUserIdentityService(identities, true),
                securityAuditTrail = SecurityAuditTrail(events::add),
                accountDeletion = AccountDeletionService { userId, firebaseUid ->
                    deleted += userId to firebaseUid
                    tombstoned = true
                },
            )
        }

        val response = client.delete("/api/v1/auth/me") { bearerAuth("firebase-id-token") }
        val retry = client.delete("/api/v1/auth/me") { bearerAuth("firebase-id-token") }
        val profileAfterDeletion = client.get("/api/v1/auth/me") { bearerAuth("firebase-id-token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(HttpStatusCode.Unauthorized, profileAfterDeletion.status)
        assertEquals(DeleteAccountResponse(), Json.decodeFromString(response.bodyAsText()))
        assertEquals(listOf(owner.id to "sensitive-firebase-uid"), deleted)
        val audit = events.first { it.action == SecurityAuditAction.DELETE_ACCOUNT }
        assertEquals(SecurityAuditResult.SUCCESS, audit.result)
        assertEquals(owner.id, audit.actorUserId)
        assertFalse(audit.toString().contains("sensitive-firebase-uid"))
        assertFalse(audit.toString().contains("owner@example.test"))
        assertFalse(audit.toString().contains("firebase-id-token"))
    }

    @Test fun `sensitive device mutations emit safe audit events for success and rejection`() = testApplication {
        val store = MemoryStore()
        val owner = UserRecord(UUID.randomUUID(), "owner@example.test", null, OffsetDateTime.now(clock))
        val stranger = UserRecord(UUID.randomUUID(), "stranger@example.test", null, OffsetDateTime.now(clock))
        val plant = PlantRecord(UUID.randomUUID(), owner.id, "Fern", createdAt = OffsetDateTime.now(clock))
        val device = DeviceRecord(UUID.randomUUID(), plant.id, "Sensor", "old-hash", createdAt = OffsetDateTime.now(clock))
        store.plants += plant
        store.devices += device
        val events = mutableListOf<SecurityAuditEvent>()
        val verifier = FakeFirebaseTokenVerifier(
            mapOf(
                "owner-token" to Result.success(VerifiedFirebaseToken("owner-uid", owner.email, true, validUntil)),
                "stranger-token" to Result.success(VerifiedFirebaseToken("stranger-uid", stranger.email, true, validUntil)),
            ),
        )
        val identities = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String) = when (externalSubject) {
                "owner-uid" -> owner
                "stranger-uid" -> stranger
                else -> null
            }
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord) = error("unused")
        }
        application {
            configureApplication(
                AppConfig(8080, DatabaseConfig("jdbc:none", "x", "x"), emptySet(), FirebaseConfig("test-project")),
                { true },
                userApplication = UserApplicationService(store, clock = clock),
                firebaseTokenVerifier = verifier,
                firebaseUsers = FirebaseUserIdentityService(identities, true),
                securityAuditTrail = SecurityAuditTrail(events::add),
            )
        }

        val rotated = client.post("/api/v1/devices/${device.id}/rotate-token") { bearerAuth("owner-token") }
        val denied = client.post("/api/v1/devices/${device.id}/rotate-token") { bearerAuth("stranger-token") }

        assertEquals(HttpStatusCode.OK, rotated.status)
        assertEquals(HttpStatusCode.NotFound, denied.status)
        listOf(rotated, denied).forEach { response ->
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("no-cache", response.headers[HttpHeaders.Pragma])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
        }
        assertEquals(listOf(SecurityAuditResult.SUCCESS, SecurityAuditResult.REJECTED), events.map(SecurityAuditEvent::result))
        assertTrue(events.all { it.action == SecurityAuditAction.ROTATE_DEVICE_TOKEN })
        assertTrue(events.all { it.target == SecurityAuditTarget.DEVICE && it.resourceId == device.id })
        assertEquals(listOf(owner.id, stranger.id), events.map(SecurityAuditEvent::actorUserId))
        assertEquals(rotated.headers[HttpHeaders.XRequestId], events[0].requestId)
        assertEquals(denied.headers[HttpHeaders.XRequestId], events[1].requestId)
    }

    @Test fun `user API rate limit rejects before repeated Firebase verification`() = testApplication {
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("invalid-token" to Result.failure(FirebaseTokenVerificationException())),
        )
        val identities = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String): UserRecord? = null
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord =
                error("unused")
        }
        val config = AppConfig(
            8080,
            DatabaseConfig("jdbc:none", "x", "x"),
            emptySet(),
            FirebaseConfig("test-project"),
            HttpLimitConfig(userApiRequestsPerPeriod = 1),
        )
        application {
            configureApplication(
                config,
                { true },
                userApplication = UserApplicationService(MemoryStore(), clock = clock),
                firebaseTokenVerifier = verifier,
                firebaseUsers = FirebaseUserIdentityService(identities, true),
            )
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/plants") { bearerAuth("invalid-token") }.status,
        )
        val limited = client.get("/api/v1/plants") { bearerAuth("invalid-token") }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", Json.decodeFromString<ApiErrorResponse>(limited.bodyAsText()).code)
        assertEquals(listOf("invalid-token"), verifier.verifiedTokens)
    }

    @Test fun `owner can restore quarantined device while ownership remains hidden`() = testApplication {
        val store = MemoryStore()
        val owner = UserRecord(UUID.randomUUID(), "owner@example.test", null, OffsetDateTime.now(clock))
        val stranger = UserRecord(UUID.randomUUID(), "stranger@example.test", null, OffsetDateTime.now(clock))
        val plant = PlantRecord(UUID.randomUUID(), owner.id, "Fern", createdAt = OffsetDateTime.now(clock))
        val device = DeviceRecord(UUID.randomUUID(), plant.id, "Sensor", "hash", createdAt = OffsetDateTime.now(clock))
        store.plants += plant
        store.devices += device
        store.quarantinedDevices += device.id
        val events = mutableListOf<SecurityAuditEvent>()
        val verifier = FakeFirebaseTokenVerifier(
            mapOf(
                "owner-token" to Result.success(VerifiedFirebaseToken("owner-uid", owner.email, true, validUntil)),
                "stranger-token" to Result.success(VerifiedFirebaseToken("stranger-uid", stranger.email, true, validUntil)),
            ),
        )
        val identities = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String) = when (externalSubject) {
                "owner-uid" -> owner
                "stranger-uid" -> stranger
                else -> null
            }
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord) = error("unused")
        }
        application {
            configureApplication(
                AppConfig(8080, DatabaseConfig("jdbc:none", "x", "x"), emptySet(), FirebaseConfig("test-project")),
                { true },
                userApplication = UserApplicationService(store, clock = clock),
                firebaseTokenVerifier = verifier,
                firebaseUsers = FirebaseUserIdentityService(identities, true),
                securityAuditTrail = SecurityAuditTrail(events::add),
            )
        }

        val denied = client.post("/api/v1/devices/${device.id}/restore") { bearerAuth("stranger-token") }
        val restored = client.post("/api/v1/devices/${device.id}/restore") { bearerAuth("owner-token") }

        assertEquals(HttpStatusCode.NotFound, denied.status)
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals(emptySet(), store.quarantinedDevices)
        assertEquals(listOf(SecurityAuditResult.REJECTED, SecurityAuditResult.SUCCESS), events.map(SecurityAuditEvent::result))
        assertTrue(events.all { it.action == SecurityAuditAction.RESTORE_DEVICE })
        assertEquals(listOf(stranger.id, owner.id), events.map(SecurityAuditEvent::actorUserId))
        assertTrue(events.all { it.resourceId == device.id && it.target == SecurityAuditTarget.DEVICE })
    }

    @Test fun `plant and device lookups hide another users resources`() {
        val store = MemoryStore()
        val service = service(store)
        val owner = UUID.randomUUID(); val stranger = UUID.randomUUID()
        val plant = PlantRecord(UUID.randomUUID(), owner, "Fern", createdAt = OffsetDateTime.now(clock))
        store.plants += plant
        val device = DeviceRecord(UUID.randomUUID(), plant.id, "Sensor", "hash", createdAt = OffsetDateTime.now(clock))
        store.devices += device
        assertEquals("Fern", service.getPlant(owner, plant.id).name)
        assertEquals("NOT_FOUND", assertFailsWith<UserApiException> { service.getPlant(stranger, plant.id) }.code)
        assertEquals("NOT_FOUND", assertFailsWith<UserApiException> { service.getDevice(stranger, device.id) }.code)
    }

    @Test fun `claim code can only be consumed once and token rotation replaces hash`() {
        val store = MemoryStore(); val service = service(store); val owner = UUID.randomUUID()
        val plant = PlantRecord(UUID.randomUUID(), owner, "Fern", createdAt = OffsetDateTime.now(clock)); store.plants += plant
        val device = DeviceRecord(UUID.randomUUID(), null, "Sensor", "old-hash", createdAt = OffsetDateTime.now(clock)); store.devices += device
        store.claimAvailable = true
        val claimCode = "a".repeat(32)
        assertEquals(plant.id.toString(), service.claimDevice(owner, ClaimDeviceRequest(claimCode, plant.id.toString())).plantId)
        assertEquals("NOT_FOUND", assertFailsWith<UserApiException> { service.claimDevice(owner, ClaimDeviceRequest(claimCode, plant.id.toString())) }.code)
        val rotated = service.rotateDeviceToken(owner, device.id)
        assertNotEquals("old-hash", store.devices.single().tokenHash)
        assertTrue(rotated.token.length >= 64)
    }

    @Test fun `plant fields enforce database boundaries and HTTPS image URLs`() {
        val store = MemoryStore()
        val service = service(store)
        val owner = UUID.randomUUID()
        val imagePrefix = "https://example.test/"
        val accepted = service.createPlant(
            owner,
            CreatePlantRequest(
                name = "Plant",
                species = "s".repeat(255),
                imageUrl = imagePrefix + "a".repeat(2_048 - imagePrefix.length),
            ),
        )

        assertEquals(255, accepted.species?.length)
        assertEquals(2_048, accepted.imageUrl?.length)

        val invalidInputs = listOf(
            CreatePlantRequest("Plant", species = "s".repeat(256)) to "INVALID_SPECIES",
            CreatePlantRequest("Plant", species = "fern\u0000type") to "INVALID_SPECIES",
            CreatePlantRequest("Plant", imageUrl = imagePrefix + "a".repeat(2_049 - imagePrefix.length)) to "INVALID_IMAGE_URL",
            CreatePlantRequest("Plant", imageUrl = "http://example.test/image.jpg") to "INVALID_IMAGE_URL",
            CreatePlantRequest("Plant", imageUrl = "https://user:secret@example.test/image.jpg") to "INVALID_IMAGE_URL",
            CreatePlantRequest("Plant", imageUrl = "javascript:alert(1)") to "INVALID_IMAGE_URL",
        )
        invalidInputs.forEach { (request, expectedCode) ->
            assertEquals(
                expectedCode,
                assertFailsWith<UserApiException> { service.createPlant(owner, request) }.code,
            )
        }
        assertEquals(
            "INVALID_SPECIES",
            assertFailsWith<UserApiException> {
                service.updatePlant(owner, UUID.fromString(accepted.id), UpdatePlantRequest(species = "s".repeat(256)))
            }.code,
        )
    }

    @Test fun `claim code requires exact provisioned lowercase hex format`() {
        val service = service(MemoryStore())
        val userId = UUID.randomUUID()
        val plantId = UUID.randomUUID().toString()

        listOf("", "a".repeat(31), "A".repeat(32), "g".repeat(32), "a".repeat(33)).forEach { claimCode ->
            assertEquals(
                "INVALID_CLAIM_CODE",
                assertFailsWith<UserApiException> {
                    service.claimDevice(userId, ClaimDeviceRequest(claimCode, plantId))
                }.code,
            )
        }
    }

    @Test fun `Firebase routes preserve plant and device ownership by internal UUID`() = testApplication {
        val store = MemoryStore(); val config = AppConfig(8080, DatabaseConfig("jdbc:none","x","x"), emptySet(), FirebaseConfig("test-project"))
        val service = UserApplicationService(store, clock = clock)
        val owner = UserRecord(UUID.randomUUID(), "firebase@example.test", null, OffsetDateTime.now(clock))
        val stranger = UserRecord(UUID.randomUUID(), "stranger@example.test", null, OffsetDateTime.now(clock))
        val telemetryDeviceId = UUID.randomUUID()
        val telemetry = PlantTelemetryService(
            object : PlantTelemetryRepository {
                override fun latest(userId: UUID, plantId: UUID) =
                    store.findPlant(userId, plantId)?.let {
                        PlantLatestResponse(
                            plantId = plantId.toString(),
                            deviceId = telemetryDeviceId.toString(),
                            measuredAt = OffsetDateTime.now(clock).toString(),
                            receivedAt = OffsetDateTime.now(clock).toString(),
                            soilMoisturePercent = 42.0,
                            online = true,
                            calibrated = true,
                        )
                    }

                override fun history(userId: UUID, plantId: UUID, request: HistoryRequest, limit: Int) =
                    store.findPlant(userId, plantId)?.let {
                        listOf(PlantHistoryPoint(OffsetDateTime.now(clock).toString(), soilMoisturePercent = 42.0))
                    }

                override fun ownsPlant(userId: UUID, plantId: UUID) = store.findPlant(userId, plantId) != null
            },
            config.history,
            clock,
        )
        val firebaseVerifier = FakeFirebaseTokenVerifier(
            mapOf(
                "firebase-id-token" to Result.success(VerifiedFirebaseToken("firebase-uid", owner.email, true, validUntil)),
                "stranger-id-token" to Result.success(VerifiedFirebaseToken("stranger-uid", stranger.email, true, validUntil)),
                "legacy-access-token" to Result.failure(FirebaseTokenVerificationException()),
            ),
        )
        val identities = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String) = when (externalSubject) {
                "firebase-uid" -> owner
                "stranger-uid" -> stranger
                else -> null
            }
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord) = error("identity already exists")
        }
        application {
            configureApplication(
                config,
                { true },
                userApplication = service,
                plantTelemetry = telemetry,
                firebaseTokenVerifier = firebaseVerifier,
                firebaseUsers = FirebaseUserIdentityService(identities, true),
            )
        }
        listOf("register", "login", "refresh", "logout").forEach { endpoint ->
            val response = client.post("/api/v1/auth/$endpoint")
            assertEquals(HttpStatusCode.Gone, response.status)
            val body = response.bodyAsText()
            assertEquals("LEGACY_AUTH_DISABLED", Json.decodeFromString<ApiErrorResponse>(body).code)
            assertFalse(body.contains("accessToken"))
            assertFalse(body.contains("refreshToken"))
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
        val meResponse = client.get("/api/v1/auth/me") { bearerAuth("firebase-id-token") }
        assertEquals(HttpStatusCode.OK, meResponse.status)
        val meBody = meResponse.bodyAsText()
        assertEquals(
            CurrentUserResponse(owner.id.toString(), owner.email, emailVerified = true),
            Json.decodeFromString<CurrentUserResponse>(meBody),
        )
        assertEquals(setOf("id", "email", "emailVerified"), Json.parseToJsonElement(meBody).jsonObject.keys)
        assertFalse(meBody.contains("firebase-uid"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/plants").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/plants") { bearerAuth("legacy-access-token") }.status)
        val invalidPlant = client.post("/api/v1/plants") {
            bearerAuth("firebase-id-token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Aloe","imageUrl":"http://example.test/aloe.jpg"}""")
        }
        val invalidPlantError = Json.decodeFromString<ApiErrorResponse>(invalidPlant.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, invalidPlant.status)
        assertEquals("INVALID_IMAGE_URL", invalidPlantError.code)
        assertEquals(invalidPlant.headers[HttpHeaders.XRequestId], invalidPlantError.requestId)
        val created = client.post("/api/v1/plants") { bearerAuth("firebase-id-token");contentType(ContentType.Application.Json);setBody("""{"name":"Aloe"}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        val plant = Json.decodeFromString<PlantResponse>(created.bodyAsText())
        assertEquals("Aloe", plant.name)
        assertEquals(owner.id, store.plants.single().userId)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/plants/${plant.id}") { bearerAuth("firebase-id-token") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/plants/${plant.id}") { bearerAuth("stranger-id-token") }.status)

        val ownedDevice = DeviceRecord(UUID.randomUUID(), UUID.fromString(plant.id), "Owned sensor", "hash", createdAt = OffsetDateTime.now(clock))
        val strangerPlant = PlantRecord(UUID.randomUUID(), stranger.id, "Private", createdAt = OffsetDateTime.now(clock))
        val strangerDevice = DeviceRecord(UUID.randomUUID(), strangerPlant.id, "Other sensor", "hash", createdAt = OffsetDateTime.now(clock))
        store.plants += strangerPlant
        store.devices += listOf(ownedDevice, strangerDevice)
        val plants = client.get("/api/v1/plants") { bearerAuth("firebase-id-token") }
        assertEquals(HttpStatusCode.OK, plants.status)
        assertEquals(listOf(plant.id), Json.decodeFromString<List<PlantResponse>>(plants.bodyAsText()).map(PlantResponse::id))
        val devices = client.get("/api/v1/devices") { bearerAuth("firebase-id-token") }
        assertEquals(HttpStatusCode.OK, devices.status)
        assertEquals(listOf(ownedDevice.id.toString()), Json.decodeFromString<List<DeviceResponse>>(devices.bodyAsText()).map(DeviceResponse::id))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/devices/${ownedDevice.id}") { bearerAuth("stranger-id-token") }.status)

        val latest = client.get("/api/v1/plants/${plant.id}/latest") { bearerAuth("firebase-id-token") }
        assertEquals(HttpStatusCode.OK, latest.status)
        assertEquals(42.0, Json.decodeFromString<PlantLatestResponse>(latest.bodyAsText()).soilMoisturePercent)
        val history = client.get("/api/v1/plants/${plant.id}/history") { bearerAuth("firebase-id-token") }
        assertEquals(HttpStatusCode.OK, history.status)
        assertEquals(1, Json.decodeFromString<PlantHistoryResponse>(history.bodyAsText()).points.size)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/plants/${plant.id}/latest") { bearerAuth("stranger-id-token") }.status)
    }

    @Test fun `SSE closes at configured maximum lifetime`() = testApplication {
        val stream = streamFixture(
            history = HistoryConfig(
                heartbeatSeconds = 1,
                streamMaxLifetimeSeconds = 1,
                streamOwnershipRecheckSeconds = 1,
            ),
            ownsPlant = { true },
        )
        application { stream.configure(this) }

        val startedAt = System.nanoTime()
        val response = client.get("/api/v1/plants/${stream.plantId}/stream") {
            bearerAuth("firebase-id-token")
        }
        val body = response.bodyAsText()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains(": connected"))
        assertTrue(elapsedMillis in 500..4_000, "stream lifetime was $elapsedMillis ms")
    }

    @Test fun `SSE closes when Firebase ID token expires`() = testApplication {
        val stream = streamFixture(
            history = HistoryConfig(
                heartbeatSeconds = 1,
                streamMaxLifetimeSeconds = 5,
                streamOwnershipRecheckSeconds = 1,
            ),
            ownsPlant = { true },
            tokenExpiresAt = Instant.now().plusSeconds(2),
        )
        application { stream.configure(this) }

        val startedAt = System.nanoTime()
        val response = client.get("/api/v1/plants/${stream.plantId}/stream") {
            bearerAuth("firebase-id-token")
        }
        response.bodyAsText()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(elapsedMillis in 1_000..4_000, "token expiry closed stream after $elapsedMillis ms")
    }

    @Test fun `SSE closes when periodic ownership recheck fails`() = testApplication {
        val ownershipChecks = AtomicInteger()
        val stream = streamFixture(
            history = HistoryConfig(
                heartbeatSeconds = 1,
                streamMaxLifetimeSeconds = 5,
                streamOwnershipRecheckSeconds = 1,
            ),
            ownsPlant = { ownershipChecks.incrementAndGet() == 1 },
        )
        application { stream.configure(this) }

        val startedAt = System.nanoTime()
        val response = client.get("/api/v1/plants/${stream.plantId}/stream") {
            bearerAuth("firebase-id-token")
        }
        response.bodyAsText()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, ownershipChecks.get())
        assertTrue(elapsedMillis in 500..4_000, "ownership was rechecked after $elapsedMillis ms")
    }

    private fun streamFixture(
        history: HistoryConfig,
        ownsPlant: () -> Boolean,
        tokenExpiresAt: Instant = validUntil,
    ): StreamFixture {
        val store = MemoryStore()
        val owner = UserRecord(UUID.randomUUID(), "stream@example.test", null, OffsetDateTime.now(clock))
        val plant = PlantRecord(UUID.randomUUID(), owner.id, "Stream plant", createdAt = OffsetDateTime.now(clock))
        store.plants += plant
        val telemetry = PlantTelemetryService(
            object : PlantTelemetryRepository {
                override fun latest(userId: UUID, plantId: UUID): PlantLatestResponse? = error("unused")
                override fun history(userId: UUID, plantId: UUID, request: HistoryRequest, limit: Int): List<PlantHistoryPoint>? = error("unused")
                override fun ownsPlant(userId: UUID, plantId: UUID): Boolean = ownsPlant()
            },
            history,
        )
        val identities = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String) = owner
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord) = error("unused")
        }
        val config = AppConfig(
            8080,
            DatabaseConfig("jdbc:none", "x", "x"),
            emptySet(),
            FirebaseConfig("test-project"),
            history = history,
        )
        return StreamFixture(
            plantId = plant.id,
            configure = { application ->
                application.configureApplication(
                    config,
                    { true },
                    userApplication = UserApplicationService(store, clock = clock),
                    plantTelemetry = telemetry,
                    eventBus = MeasurementEventBus(),
                    firebaseTokenVerifier = FakeFirebaseTokenVerifier(
                        mapOf(
                            "firebase-id-token" to Result.success(
                                VerifiedFirebaseToken("firebase-uid", owner.email, true, tokenExpiresAt),
                            ),
                        ),
                    ),
                    firebaseUsers = FirebaseUserIdentityService(identities, true),
                )
            },
        )
    }

    private fun service(store: MemoryStore) = UserApplicationService(store, clock = clock)
    private val clock = Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC)
    private val validUntil = Instant.parse("2100-01-01T00:00:00Z")
}

private data class StreamFixture(
    val plantId: UUID,
    val configure: (io.ktor.server.application.Application) -> Unit,
)

private class MemoryStore : UserApplicationStore {
    val plants=mutableListOf<PlantRecord>(); val devices=mutableListOf<DeviceRecord>(); var claimAvailable=false
    val quarantinedDevices=mutableSetOf<UUID>()
    override fun listPlants(userId:UUID)=plants.filter{it.userId==userId&&it.archivedAt==null}
    override fun findPlant(userId:UUID,plantId:UUID)=listPlants(userId).find{it.id==plantId}
    override fun createPlant(plant:PlantRecord)=plant.also{plants+=it}
    override fun updatePlant(userId:UUID,plantId:UUID,request:UpdatePlantRequest)=findPlant(userId,plantId)?.let{ old->old.copy(name=request.name?:old.name,species=request.species?:old.species,imageUrl=request.imageUrl?:old.imageUrl).also{plants[plants.indexOf(old)]=it} }
    override fun archivePlant(userId:UUID,plantId:UUID,at:OffsetDateTime)=findPlant(userId,plantId)?.let{plants[plants.indexOf(it)]=it.copy(archivedAt=at);true}?:false
    override fun claimDevice(userId:UUID,plantId:UUID,claimHash:String,now:OffsetDateTime):DeviceRecord? { if(!claimAvailable||findPlant(userId,plantId)==null)return null;claimAvailable=false;val old=devices.find{it.plantId==null}?:return null;return old.copy(plantId=plantId).also{devices[devices.indexOf(old)]=it} }
    override fun listDevices(userId:UUID)=devices.filter{d->plants.any{it.id==d.plantId&&it.userId==userId}}
    override fun findDevice(userId:UUID,deviceId:UUID)=listDevices(userId).find{it.id==deviceId}
    override fun updateDevice(userId:UUID,deviceId:UUID,name:String?,plantId:UUID?)=replace(userId,deviceId){it.copy(name=name?:it.name,plantId=plantId?:it.plantId)}
    override fun updateCalibration(userId:UUID,deviceId:UUID,dry:Int,wet:Int)=replace(userId,deviceId){it.copy(soilDryRaw=dry,soilWetRaw=wet)}
    override fun rotateDeviceToken(userId:UUID,deviceId:UUID,tokenHash:String)=replace(userId,deviceId){it.copy(tokenHash=tokenHash)}
    override fun restoreDevice(userId:UUID,deviceId:UUID)=findDevice(userId,deviceId)?.also{quarantinedDevices-=deviceId}
    private fun replace(userId:UUID,id:UUID,change:(DeviceRecord)->DeviceRecord)=findDevice(userId,id)?.let{old->change(old).also{devices[devices.indexOf(old)]=it}}
}
