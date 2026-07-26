package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.plants.PlantRecord
import com.alad1nks.jaiqal.telemetry.NewMeasurement
import com.alad1nks.jaiqal.telemetry.HistoryRequest
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.users.UserRecord
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PersistenceIntegrationTest {
    @Test
    fun `migration is repeatable and creates every step 2 table`() {
        infrastructure.migrate()
        val secondRun = Flyway.configure().dataSource(infrastructure.dataSource).load().migrate()

        assertEquals(0, secondRun.migrationsExecuted)
        infrastructure.dataSource.connection.use { connection ->
            val expected = setOf(
                "users", "plants", "devices", "measurements", "device_latest_state",
                "refresh_tokens", "user_identities", "alert_rules", "alert_events", "notification_outbox", "device_claim_codes",
            )
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rows ->
                val actual = buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
                assertEquals(emptySet(), expected - actual)
            }
        }
    }

    @Test
    fun `firebase identity provisioning is idempotent under concurrent first requests`() = runBlocking {
        val repository = JdbcFirebaseIdentityRepository(infrastructure.dataSource)
        val token = VerifiedFirebaseToken("concurrent-firebase-uid-${UUID.randomUUID()}", null, false)

        assertNull(repository.resolve(token, autoProvision = false))
        val userIds = List(8) { async { repository.resolve(token, autoProvision = true) } }.awaitAll()

        assertEquals(1, userIds.filterNotNull().toSet().size)
        assertEquals(userIds.first(), repository.resolve(token, autoProvision = false))
    }

    @Test
    fun `measurement insertion is idempotent per device and sequence`() {
        val ids = fixture()
        val repository = ExposedMeasurementRepository(infrastructure.database)
        val measurement = NewMeasurement(ids.deviceId, 7, now, now, soilMoistureRaw = 1500)

        assertNotNull(repository.insert(measurement))
        assertNull(repository.insert(measurement))
        assertNotNull(repository.findByDeviceAndSequence(ids.deviceId, 7))
    }

    @Test
    fun `database prevents deleting a user with plants and devices`() {
        val ids = fixture()

        assertFailsWith<SQLException> {
            infrastructure.dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, ids.userId)
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `claim code is consumed exactly once`() {
        val user = UserRecord(UUID.randomUUID(), "${UUID.randomUUID()}-claim@example.test", "argon2-hash", now)
        ExposedUserRepository(infrastructure.database).create(user)
        val plant = PlantRecord(UUID.randomUUID(), user.id, "Claim plant", createdAt = now)
        ExposedPlantRepository(infrastructure.database).create(plant)
        val device = DeviceRecord(UUID.randomUUID(), null, "Unclaimed", DeviceTokens.hashHex("device-token"), createdAt = now)
        ExposedDeviceRepository(infrastructure.database).create(device)
        infrastructure.dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO device_claim_codes(id,device_id,code_hash,expires_at,created_at) VALUES(?,?,?,?,?)").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, device.id)
                it.setString(3, DeviceTokens.hashHex("one-time-code")); it.setObject(4, now.plusHours(1)); it.setObject(5, now)
                it.executeUpdate()
            }
            connection.commit()
        }
        val store = JdbcUserApplicationStore(infrastructure.dataSource)
        assertNotNull(store.claimDevice(user.id, plant.id, DeviceTokens.hashHex("one-time-code"), now))
        assertNull(store.claimDevice(user.id, plant.id, DeviceTokens.hashHex("one-time-code"), now))
    }

    @Test
    fun `latest uses state table and history aggregates in SQL buckets`() {
        val ids = fixture()
        val measurements = ExposedMeasurementRepository(infrastructure.database)
        measurements.insert(NewMeasurement(ids.deviceId, 100, now.minusMinutes(4), now.minusMinutes(4), soilMoistureRaw = 1000, soilMoisturePercent = 20.0))!!
        val second = measurements.insert(NewMeasurement(ids.deviceId, 101, now.minusMinutes(2), now.minusMinutes(2), soilMoistureRaw = 2000, soilMoisturePercent = 40.0))!!
        measurements.upsertLatest(com.alad1nks.jaiqal.telemetry.LatestDeviceState(ids.deviceId, second.id, now))
        val repository = JdbcPlantTelemetryRepository(infrastructure.dataSource)

        assertEquals(2000, repository.latest(ids.userId, ids.plantId)?.soilMoistureRaw)
        val points = repository.history(ids.userId, ids.plantId, HistoryRequest(now.minusMinutes(5), now, HistoryInterval.FIVE_MINUTES), 10)
        assertEquals(1, points?.size)
        assertEquals(30.0, points?.single()?.soilMoisturePercent)
        assertNull(repository.history(UUID.randomUUID(), ids.plantId, HistoryRequest(now.minusMinutes(5), now, HistoryInterval.RAW), 10))
    }

    private fun fixture(): FixtureIds {
        val suffix = UUID.randomUUID()
        val user = UserRecord(UUID.randomUUID(), "$suffix@example.test", "argon2-hash", now)
        ExposedUserRepository(infrastructure.database).create(user)
        val plant = PlantRecord(UUID.randomUUID(), user.id, "Fern", createdAt = now)
        ExposedPlantRepository(infrastructure.database).create(plant)
        val device = DeviceRecord(UUID.randomUUID(), plant.id, "Sensor", "sha256-hash", createdAt = now)
        ExposedDeviceRepository(infrastructure.database).create(device)
        return FixtureIds(user.id, plant.id, device.id)
    }

    private data class FixtureIds(val userId: UUID, val plantId: UUID, val deviceId: UUID)

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")
        private lateinit var infrastructure: DatabaseInfrastructure
        private val now: OffsetDateTime = OffsetDateTime.of(2026, 7, 26, 12, 0, 0, 0, ZoneOffset.UTC)

        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            postgres.start()
            infrastructure = DatabaseInfrastructure.create(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password))
            infrastructure.migrate()
        }

        @JvmStatic
        @AfterClass
        fun afterAll() {
            if (::infrastructure.isInitialized) infrastructure.close()
            postgres.stop()
        }
    }
}
