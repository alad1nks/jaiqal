package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.plants.PlantRecord
import com.alad1nks.jaiqal.telemetry.NewMeasurement
import com.alad1nks.jaiqal.users.UserRecord
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
                "refresh_tokens", "alert_rules", "alert_events", "notification_outbox",
            )
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rows ->
                val actual = buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
                assertEquals(emptySet(), expected - actual)
            }
        }
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

    private fun fixture(): FixtureIds {
        val suffix = UUID.randomUUID()
        val user = UserRecord(UUID.randomUUID(), "$suffix@example.test", "argon2-hash", now)
        ExposedUserRepository(infrastructure.database).create(user)
        val plant = PlantRecord(UUID.randomUUID(), user.id, "Fern", createdAt = now)
        ExposedPlantRepository(infrastructure.database).create(plant)
        val device = DeviceRecord(UUID.randomUUID(), plant.id, "Sensor", "sha256-hash", createdAt = now)
        ExposedDeviceRepository(infrastructure.database).create(device)
        return FixtureIds(user.id, device.id)
    }

    private data class FixtureIds(val userId: UUID, val deviceId: UUID)

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
