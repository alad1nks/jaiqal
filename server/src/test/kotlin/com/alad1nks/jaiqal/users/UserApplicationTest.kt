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
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

class UserApplicationTest {
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
        assertEquals(plant.id.toString(), service.claimDevice(owner, ClaimDeviceRequest("claim", plant.id.toString())).plantId)
        assertEquals("NOT_FOUND", assertFailsWith<UserApiException> { service.claimDevice(owner, ClaimDeviceRequest("claim", plant.id.toString())) }.code)
        val rotated = service.rotateDeviceToken(owner, device.id)
        assertNotEquals("old-hash", store.devices.single().tokenHash)
        assertTrue(rotated.token.length >= 64)
    }

    @Test fun `Firebase routes preserve plant and device ownership by internal UUID`() = testApplication {
        val store = MemoryStore(); val config = AppConfig(8080, DatabaseConfig("jdbc:none","x","x"), emptySet(), FirebaseConfig("test-project"))
        val service = UserApplicationService(store, clock = clock)
        val owner = UserRecord(UUID.randomUUID(), "firebase@example.test", null, OffsetDateTime.now(clock))
        val stranger = UserRecord(UUID.randomUUID(), "stranger@example.test", null, OffsetDateTime.now(clock))
        val firebaseVerifier = FakeFirebaseTokenVerifier(
            mapOf(
                "firebase-id-token" to Result.success(VerifiedFirebaseToken("firebase-uid", owner.email, true)),
                "stranger-id-token" to Result.success(VerifiedFirebaseToken("stranger-uid", stranger.email, true)),
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
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/plants").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/plants") { bearerAuth("legacy-access-token") }.status)
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
        val devices = client.get("/api/v1/devices") { bearerAuth("firebase-id-token") }
        assertEquals(HttpStatusCode.OK, devices.status)
        assertEquals(listOf(ownedDevice.id.toString()), Json.decodeFromString<List<DeviceResponse>>(devices.bodyAsText()).map(DeviceResponse::id))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/devices/${ownedDevice.id}") { bearerAuth("stranger-id-token") }.status)
    }

    private fun service(store: MemoryStore) = UserApplicationService(store, clock = clock)
    private val clock = Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC)
}

private class MemoryStore : UserApplicationStore {
    val plants=mutableListOf<PlantRecord>(); val devices=mutableListOf<DeviceRecord>(); var claimAvailable=false
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
    private fun replace(userId:UUID,id:UUID,change:(DeviceRecord)->DeviceRecord)=findDevice(userId,id)?.let{old->change(old).also{devices[devices.indexOf(old)]=it}}
}
