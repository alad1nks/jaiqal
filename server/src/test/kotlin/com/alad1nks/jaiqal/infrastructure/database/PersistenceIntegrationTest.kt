package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.auth.DevicePrincipal
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.MigrationDatabaseConfig
import com.alad1nks.jaiqal.config.TelemetryConfig
import com.alad1nks.jaiqal.config.TelemetryRetentionConfig
import com.alad1nks.jaiqal.config.CapacityMonitoringConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.plants.PlantRecord
import com.alad1nks.jaiqal.telemetry.NewMeasurement
import com.alad1nks.jaiqal.telemetry.HistoryRequest
import com.alad1nks.jaiqal.telemetry.PreparedMeasurement
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.users.UserRecord
import com.alad1nks.jaiqal.users.FirebaseUserIdentityService
import com.alad1nks.jaiqal.users.UnknownFirebaseIdentityException
import com.alad1nks.jaiqal.config.AlertConfig
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import com.alad1nks.jaiqal.notifications.NotificationSender
import com.alad1nks.jaiqal.notifications.NotificationWorker
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.time.Instant
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PersistenceIntegrationTest {
    private val validUntil = Instant.parse("2100-01-01T00:00:00Z")

    @Test
    fun `migration is repeatable and creates every application table`() {
        DatabaseMigrator.migrate(migrationConfig())
        val secondRun = Flyway.configure().dataSource(infrastructure.dataSource).load().migrate()

        assertEquals(0, secondRun.migrationsExecuted)
        infrastructure.dataSource.connection.use { connection ->
            val expected = setOf(
                "users", "plants", "devices", "measurements", "device_latest_state",
                "refresh_tokens", "alert_rules", "alert_events", "notification_outbox", "device_claim_codes",
                "user_identities",
                "device_ingestion_quotas",
            )
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE", "PARTITIONED TABLE")).use { rows ->
                val actual = buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
                assertEquals(emptySet(), expected - actual)
            }
        }
    }

    @Test
    fun `readiness burst returns every borrowed connection to the bounded pool`() {
        val readiness = DataSourceDatabaseReadiness(infrastructure.dataSource)
        val executor = Executors.newFixedThreadPool(32)
        try {
            val results = (0 until 64).map {
                executor.submit(Callable { runBlocking { readiness.isReady() } })
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertTrue(results.all { it })
            assertEquals(0, infrastructure.dataSource.hikariPoolMXBean.activeConnections)
            assertTrue(
                infrastructure.dataSource.hikariPoolMXBean.totalConnections <=
                    infrastructure.dataSource.maximumPoolSize,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `notification retry persists only a safe error code`() {
        val ids = fixture()
        val alertId = UUID.randomUUID()
        val notificationKey = "$alertId:test:LOG"
        val secret = "Bearer provider-secret-${UUID.randomUUID()}"
        infrastructure.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO alert_events(id,plant_id,type,status,triggered_at,last_observed_at)
                    VALUES(?,?,'DEVICE_OFFLINE','RECOVERED',?,?)""",
            ).use { statement ->
                statement.setObject(1, alertId)
                statement.setObject(2, ids.plantId)
                statement.setObject(3, now)
                statement.setObject(4, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO notification_outbox(alert_event_id,channel,payload,status,attempts,available_at,created_at,notification_key)
                    VALUES(?,'LOG','{}'::jsonb,'PENDING',0,?,?,?)""",
            ).use { statement ->
                statement.setObject(1, alertId)
                statement.setObject(2, now)
                statement.setObject(3, now)
                statement.setString(4, notificationKey)
                statement.executeUpdate()
            }
            connection.commit()
        }
        val worker = NotificationWorker(
            dataSource = infrastructure.dataSource,
            sender = NotificationSender { throw IllegalStateException("Provider rejected $secret") },
            config = AlertConfig(outboxBatchSize = 100),
            workerId = "safe-error-test-${UUID.randomUUID()}",
            clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC),
        )

        assertTrue(worker.runOnce() >= 1)

        infrastructure.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT last_error FROM notification_outbox WHERE notification_key=?",
            ).use { statement ->
                statement.setString(1, notificationKey)
                statement.executeQuery().use { row ->
                    assertTrue(row.next())
                    assertEquals("DELIVERY_FAILED", row.getString(1))
                    assertFalse(row.getString(1).contains(secret))
                }
            }
        }
        assertFailsWith<SQLException> {
            infrastructure.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE notification_outbox SET last_error=? WHERE notification_key=?",
                ).use { statement ->
                    statement.setString(1, secret)
                    statement.setString(2, notificationKey)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `measurements use fixed hash partitions and capacity monitor includes their storage`() {
        infrastructure.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM pg_partition_tree('measurements'::regclass) WHERE isleaf",
                ).use { row ->
                    assertTrue(row.next())
                    assertEquals(16, row.getInt(1))
                }
            }
        }

        DatabaseCapacityMonitor(
            infrastructure.dataSource,
            CapacityMonitoringConfig(
                measurementsWarnRows = Long.MAX_VALUE,
                measurementsWarnBytes = Long.MAX_VALUE,
                databaseWarnBytes = Long.MAX_VALUE,
            ),
        ).check()
    }

    @Test
    fun `retention deletes old measurements in batches but preserves latest state`() {
        val ids = fixture()
        val measurements = ExposedMeasurementRepository(infrastructure.database)
        val expired = measurements.insert(
            NewMeasurement(ids.deviceId, 700, now.minusDays(60), now.minusDays(60), lightRaw = 1),
        )!!
        val protectedLatest = measurements.insert(
            NewMeasurement(ids.deviceId, 701, now.minusDays(45), now.minusDays(45), lightRaw = 2),
        )!!
        val recent = measurements.insert(
            NewMeasurement(ids.deviceId, 702, now.minusDays(1), now.minusDays(1), lightRaw = 3),
        )!!
        measurements.upsertLatest(
            com.alad1nks.jaiqal.telemetry.LatestDeviceState(ids.deviceId, protectedLatest.id, now),
        )
        val worker = TelemetryRetentionWorker(
            infrastructure.dataSource,
            TelemetryRetentionConfig(retentionDays = 30, intervalSeconds = 60, batchSize = 1, maxBatchesPerRun = 10),
            Clock.fixed(now.toInstant(), ZoneOffset.UTC),
        )

        assertEquals(1, worker.runOnce())
        infrastructure.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id FROM measurements WHERE device_id=? AND id IN (?,?,?) ORDER BY id",
            ).use { statement ->
                statement.setObject(1, ids.deviceId)
                statement.setLong(2, expired.id)
                statement.setLong(3, protectedLatest.id)
                statement.setLong(4, recent.id)
                statement.executeQuery().use { rows ->
                    val retained = buildList { while (rows.next()) add(rows.getLong(1)) }
                    assertEquals(listOf(protectedLatest.id, recent.id).sorted(), retained)
                }
            }
        }
    }

    @Test
    fun `Flyway uses migration role while application pool keeps non-DDL runtime role`() {
        val runtimeRole = "runtime_${UUID.randomUUID().toString().replace("-", "")}"
        val runtimePassword = "runtime-test-password"
        infrastructure.dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("CREATE ROLE $runtimeRole LOGIN PASSWORD '$runtimePassword'")
            }
            connection.commit()
        }

        val separated = DatabaseInfrastructure.create(
            DatabaseConfig(
                url = postgres.jdbcUrl,
                user = runtimeRole,
                password = runtimePassword,
            ),
        )
        try {
            DatabaseMigrator.migrate(migrationConfig())
            separated.verifyRuntimeHasNoDdlPrivileges()
            separated.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT current_user, has_schema_privilege(current_user, current_schema(), 'CREATE')",
                    ).use { row ->
                        assertTrue(row.next())
                        assertEquals(runtimeRole, row.getString(1))
                        assertEquals(false, row.getBoolean(2))
                    }
                }
            }
        } finally {
            separated.close()
            infrastructure.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP ROLE $runtimeRole") }
                connection.commit()
            }
        }
    }

    @Test
    fun `firebase identity migration advances an existing empty schema`() {
        val schema = "migration_${UUID.randomUUID().toString().replace("-", "")}"
        infrastructure.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            connection.commit()
        }
        try {
            Flyway.configure()
                .dataSource(infrastructure.dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("3")
                .load()
                .migrate()
            Flyway.configure()
                .dataSource(infrastructure.dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate()

            infrastructure.dataSource.connection.use { connection ->
                connection.metaData.getTables(null, schema, "user_identities", arrayOf("TABLE")).use { rows ->
                    assertEquals(true, rows.next())
                }
            }
        } finally {
            infrastructure.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                connection.commit()
            }
        }
    }

    @Test
    fun `device token hash migration normalizes valid legacy hashes and creates unique index`() {
        val schema = "token_migration_${UUID.randomUUID().toString().replace("-", "")}"
        infrastructure.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            connection.commit()
        }
        try {
            val flyway = Flyway.configure()
                .dataSource(infrastructure.dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("5")
                .load()
            flyway.migrate()
            val deviceId = UUID.randomUUID()
            infrastructure.dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO $schema.devices(id,name,token_hash,created_at) VALUES(?,?,?,?)").use {
                    it.setObject(1, deviceId)
                    it.setString(2, "Legacy device")
                    it.setString(3, "A".repeat(64))
                    it.setObject(4, now)
                    it.executeUpdate()
                }
                connection.commit()
            }

            Flyway.configure()
                .dataSource(infrastructure.dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate()

            infrastructure.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT token_hash FROM $schema.devices WHERE id=?").use {
                    it.setObject(1, deviceId)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("a".repeat(64), rows.getString(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='devices_token_hash_unique_idx'",
                ).use {
                    it.setString(1, schema)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getString(1).contains("UNIQUE INDEX"))
                    }
                }
            }
        } finally {
            infrastructure.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                connection.commit()
            }
        }
    }

    @Test
    fun `firebase auto provisioning is passwordless idempotent and supports no email`() {
        val store = JdbcUserIdentityStore(infrastructure.dataSource)
        val service = FirebaseUserIdentityService(store, autoProvisionUsers = true)
        val uid = "uid-${UUID.randomUUID()}"

        val first = service.resolve(VerifiedFirebaseToken(uid, null, false, validUntil))
        val repeated = service.resolve(VerifiedFirebaseToken(uid, null, false, validUntil))

        assertNull(first.email)
        assertNull(first.passwordHash)
        assertEquals(first, repeated)
    }

    @Test
    fun `disabled provisioning refuses an unknown Firebase UID`() {
        val service = FirebaseUserIdentityService(JdbcUserIdentityStore(infrastructure.dataSource), false)

        assertFailsWith<UnknownFirebaseIdentityException> {
            service.resolve(VerifiedFirebaseToken("unknown-${UUID.randomUUID()}", null, false, validUntil))
        }
    }

    @Test
    fun `concurrent first Firebase login creates one internal account`() {
        val uid = "concurrent-${UUID.randomUUID()}"
        val email = "$uid@example.test"
        val service = FirebaseUserIdentityService(JdbcUserIdentityStore(infrastructure.dataSource), true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = List(2) {
                Callable { service.resolve(VerifiedFirebaseToken(uid, email, true, validUntil)) }
            }
            val users = executor.invokeAll(tasks).map { it.get() }

            assertEquals(1, users.map(UserRecord::id).toSet().size)
            assertEquals(1, identityCount(uid))
            assertEquals(1, userCountByEmail(email))
        } finally {
            executor.shutdownNow()
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
    fun `telemetry store reports a repeated sequence as duplicate`() {
        val ids = fixture()
        val device = ExposedDeviceRepository(infrastructure.database).findById(ids.deviceId)!!
        val store = ExposedTelemetryStore(infrastructure.database)
        val measurement = PreparedMeasurement(
            NewMeasurement(ids.deviceId, 8, now, now, soilMoistureRaw = 1500),
            firmwareVersion = "test",
        )

        assertEquals(false, store.ingest(device, listOf(measurement)).single().duplicate)
        assertEquals(true, store.ingest(device, listOf(measurement)).single().duplicate)
    }

    @Test
    fun `device authentication uses token hash index and preserves disabled state`() = runBlocking {
        val ids = fixture()
        val authenticator = ExposedDeviceTokenAuthenticator(infrastructure.database)

        assertEquals(DevicePrincipal(ids.deviceId, disabled = false), authenticator.authenticate(ids.deviceToken))
        assertNull(authenticator.authenticate("wrong-${ids.deviceToken}"))

        infrastructure.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE devices SET disabled_at=? WHERE id=?").use {
                it.setObject(1, now)
                it.setObject(2, ids.deviceId)
                it.executeUpdate()
            }
            connection.commit()
        }
        assertEquals(DevicePrincipal(ids.deviceId, disabled = true), authenticator.authenticate(ids.deviceToken))

        infrastructure.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("SET enable_seqscan=off") }
            connection.prepareStatement("EXPLAIN SELECT id, disabled_at FROM devices WHERE token_hash=?").use {
                it.setString(1, DeviceTokens.hashHex(ids.deviceToken))
                it.executeQuery().use { rows ->
                    val plan = buildList { while (rows.next()) add(rows.getString(1)) }.joinToString("\n")
                    assertTrue(plan.contains("devices_token_hash_unique_idx"), plan)
                }
            }
            connection.rollback()
        }
    }

    @Test
    fun `device ingestion quota is atomic across concurrent consumers`() {
        val ids = fixture()
        val quota = JdbcDeviceIngestionQuota(
            infrastructure.dataSource,
            TelemetryConfig(quotaPeriodSeconds = 3_600, quotaMaxMeasurements = 100),
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val decisions = executor.invokeAll(
                List(2) { Callable { quota.tryConsume(ids.deviceId, 60) } },
            ).map { it.get() }

            assertEquals(1, decisions.count { it.allowed })
            assertEquals(1, decisions.count { !it.allowed && it.retryAfterSeconds > 0 })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `distinct breached quota windows trigger temporary quarantine once and owner can restore`() = runBlocking {
        val ids = fixture()
        val auditEvents = mutableListOf<SecurityAuditEvent>()
        val config = TelemetryConfig(
            quotaPeriodSeconds = 60,
            quotaMaxMeasurements = 100,
            anomalyBreachWindows = 3,
            anomalyWindowSeconds = 600,
            quarantineSeconds = 300,
        )
        val anomalyBase = OffsetDateTime.of(2099, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        repeat(3) { index ->
            val windowNow = anomalyBase.plusSeconds(index * 61L)
            val quota = JdbcDeviceIngestionQuota(
                infrastructure.dataSource,
                config,
                Clock.fixed(windowNow.toInstant(), ZoneOffset.UTC),
                SecurityAuditTrail(auditEvents::add),
            )
            assertTrue(quota.tryConsume(ids.deviceId, 100).allowed)
            val firstRejection = quota.tryConsume(ids.deviceId, 1)
            assertFalse(firstRejection.allowed)
            assertEquals(index == 2, firstRejection.quarantined)
            if (index == 0) {
                assertFalse(quota.tryConsume(ids.deviceId, 1).quarantined)
            }
        }

        assertEquals(listOf(SecurityAuditAction.QUARANTINE_DEVICE), auditEvents.map(SecurityAuditEvent::action))
        assertEquals(ids.deviceId, auditEvents.single().resourceId)
        val quarantined = ExposedDeviceTokenAuthenticator(infrastructure.database).authenticate(ids.deviceToken)
        assertTrue(requireNotNull(quarantined?.quarantinedUntil).isAfter(Instant.now()))

        val restored = JdbcUserApplicationStore(infrastructure.dataSource).restoreDevice(ids.userId, ids.deviceId)
        assertNotNull(restored)
        assertNull(ExposedDeviceTokenAuthenticator(infrastructure.database).authenticate(ids.deviceToken)?.quarantinedUntil)
        assertNull(JdbcUserApplicationStore(infrastructure.dataSource).restoreDevice(UUID.randomUUID(), ids.deviceId))
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
        val deviceToken = "device-token-$suffix"
        val user = UserRecord(UUID.randomUUID(), "$suffix@example.test", "argon2-hash", now)
        ExposedUserRepository(infrastructure.database).create(user)
        val plant = PlantRecord(UUID.randomUUID(), user.id, "Fern", createdAt = now)
        ExposedPlantRepository(infrastructure.database).create(plant)
        val device = DeviceRecord(UUID.randomUUID(), plant.id, "Sensor", DeviceTokens.hashHex(deviceToken), createdAt = now)
        ExposedDeviceRepository(infrastructure.database).create(device)
        return FixtureIds(user.id, plant.id, device.id, deviceToken)
    }

    private fun identityCount(uid: String): Int = infrastructure.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM user_identities WHERE provider='firebase' AND external_subject=?").use {
            it.setString(1, uid)
            it.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    private fun userCountByEmail(email: String): Int = infrastructure.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM users WHERE email=?").use {
            it.setString(1, email)
            it.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    private data class FixtureIds(val userId: UUID, val plantId: UUID, val deviceId: UUID, val deviceToken: String)

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")
        private lateinit var infrastructure: DatabaseInfrastructure
        private val now: OffsetDateTime = OffsetDateTime.of(2026, 7, 26, 12, 0, 0, 0, ZoneOffset.UTC)

        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            postgres.start()
            infrastructure = DatabaseInfrastructure.create(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password))
            DatabaseMigrator.migrate(migrationConfig())
        }

        @JvmStatic
        @AfterClass
        fun afterAll() {
            if (::infrastructure.isInitialized) infrastructure.close()
            postgres.stop()
        }

        private fun migrationConfig() = MigrationDatabaseConfig(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )
    }
}
