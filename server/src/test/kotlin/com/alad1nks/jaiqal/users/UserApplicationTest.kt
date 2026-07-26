package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.config.JwtConfig
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
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

class UserApplicationTest {
    @Test fun `registration normalizes email hashes password and refresh rotates once`() {
        val store = MemoryStore()
        val service = service(store)
        val auth = service.register(RegisterRequest("  USER@Example.COM ", "correct horse battery"))
        assertEquals("user@example.com", auth.user.email)
        assertFalse(store.users.single().passwordHash.contains("correct horse battery"))

        val rotated = service.refresh(RefreshRequest(auth.refreshToken))
        assertNotEquals(auth.refreshToken, rotated.refreshToken)
        val replay = assertFailsWith<UserApiException> { service.refresh(RefreshRequest(auth.refreshToken)) }
        assertEquals("INVALID_REFRESH_TOKEN", replay.code)
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
        assertEquals(plant.id.toString(), service.claimDevice(owner, ClaimDeviceRequest("claim", plant.id.toString())).plantId)
        assertEquals("NOT_FOUND", assertFailsWith<UserApiException> { service.claimDevice(owner, ClaimDeviceRequest("claim", plant.id.toString())) }.code)
        val rotated = service.rotateDeviceToken(owner, device.id)
        assertNotEquals("old-hash", store.devices.single().tokenHash)
        assertTrue(rotated.token.length >= 64)
    }

    @Test fun `auth and plant routes require and honor JWT ownership`() = testApplication {
        val store = MemoryStore(); val config = AppConfig(8080, DatabaseConfig("jdbc:none","x","x"), JwtConfig("issuer","audience","a-long-test-secret",60,600), emptySet())
        val service = UserApplicationService(store, config.jwt)
        application { configureApplication(config, { true }, userApplication = service) }
        val registration = client.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); setBody("""{"email":"route@example.com","password":"correct horse battery"}""") }
        assertEquals(HttpStatusCode.Created, registration.status)
        val auth = Json.decodeFromString<AuthResponse>(registration.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/plants").status)
        val created = client.post("/api/v1/plants") { bearerAuth(auth.accessToken);contentType(ContentType.Application.Json);setBody("""{"name":"Aloe"}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        val plant = Json.decodeFromString<PlantResponse>(created.bodyAsText())
        assertEquals("Aloe", plant.name)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/plants/${plant.id}") { bearerAuth(auth.accessToken) }.status)
    }

    private fun service(store: MemoryStore) = UserApplicationService(store, JwtConfig("issuer", "audience", "a-long-test-secret", 60, 600), clock = clock)
    private val clock = Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC)
}

private class MemoryStore : UserApplicationStore {
    val users=mutableListOf<UserRecord>(); val sessions=mutableMapOf<String,SessionRecord>(); val plants=mutableListOf<PlantRecord>(); val devices=mutableListOf<DeviceRecord>(); var claimAvailable=false
    override fun createUser(user:UserRecord)=if(users.any{it.email==user.email})false else {users+=user;true}
    override fun findUserByEmail(email:String)=users.find{it.email==email}
    override fun createSession(session:SessionRecord){sessions[session.tokenHash]=session}
    override fun rotateSession(oldHash:String,replacement:SessionRecord):UserRecord? { val old=sessions.remove(oldHash)?:return null; val actual=replacement.copy(user=old.user);sessions[actual.tokenHash]=actual;return old.user }
    override fun revokeSession(hash:String,userId:UUID?)=sessions[hash]?.takeIf{it.user.id==userId}?.let{sessions.remove(hash);true}?:false
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
