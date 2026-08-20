package com.alad1nks.jaiqal.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun readsAllConfigurationFromEnvironment() {
        val environment = requiredEnvironment() + mapOf(
            "HTTP_PORT" to "9090",
            "ALLOWED_ORIGINS" to "https://app.example.com, http://localhost:8080",
            "HTTP_MAX_BODY_BYTES" to "32768",
            "TELEMETRY_BATCH_MAX_BODY_BYTES" to "65536",
            "RATE_LIMIT_PERIOD_SECONDS" to "30",
            "READINESS_RATE_LIMIT_REQUESTS" to "10",
            "READINESS_CACHE_TTL_MILLISECONDS" to "750",
            "USER_API_RATE_LIMIT_REQUESTS" to "50",
            "TELEMETRY_RATE_LIMIT_REQUESTS" to "20",
            "SSE_MAX_CONNECTIONS_PER_USER" to "2",
            "SSE_MAX_CONNECTIONS_PER_IP" to "4",
            "SSE_MAX_LIFETIME_SECONDS" to "240",
            "SSE_OWNERSHIP_RECHECK_SECONDS" to "20",
            "TELEMETRY_DEVICE_QUOTA_PERIOD_SECONDS" to "3600",
            "TELEMETRY_DEVICE_QUOTA_MAX_MEASUREMENTS" to "200",
            "TELEMETRY_ANOMALY_BREACH_WINDOWS" to "4",
            "TELEMETRY_ANOMALY_WINDOW_SECONDS" to "28800",
            "TELEMETRY_QUARANTINE_SECONDS" to "900",
            "CAPACITY_MONITOR_INTERVAL_SECONDS" to "120",
            "CAPACITY_MEASUREMENTS_WARN_ROWS" to "1000",
            "CAPACITY_MEASUREMENTS_WARN_BYTES" to "2000",
            "CAPACITY_DATABASE_WARN_BYTES" to "3000",
            "TELEMETRY_RETENTION_DAYS" to "730",
            "TELEMETRY_RETENTION_INTERVAL_SECONDS" to "600",
            "TELEMETRY_RETENTION_BATCH_SIZE" to "2500",
            "TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN" to "5",
        )

        val config = AppConfig.fromEnvironment(environment::get)

        assertEquals(9090, config.httpPort)
        assertEquals(
            setOf("https://app.example.com", "http://localhost:8080"),
            config.allowedOrigins,
        )
        assertEquals("jdbc:postgresql://db:5432/jaiqal", config.database.url)
        assertEquals("jaiqal-test", config.firebase.projectId)
        assertEquals(false, config.firebase.checkRevokedTokens)
        assertEquals(true, config.firebase.autoProvisionUsers)
        assertEquals(32_768, config.httpLimits.maxBodyBytes)
        assertEquals(65_536, config.httpLimits.telemetryBatchMaxBodyBytes)
        assertEquals(30, config.httpLimits.rateLimitPeriodSeconds)
        assertEquals(10, config.httpLimits.readinessRequestsPerPeriod)
        assertEquals(750, config.httpLimits.readinessCacheTtlMilliseconds)
        assertEquals(50, config.httpLimits.userApiRequestsPerPeriod)
        assertEquals(20, config.httpLimits.telemetryRequestsPerPeriod)
        assertEquals(2, config.httpLimits.sseMaxConnectionsPerUser)
        assertEquals(4, config.httpLimits.sseMaxConnectionsPerIp)
        assertEquals(240, config.history.streamMaxLifetimeSeconds)
        assertEquals(20, config.history.streamOwnershipRecheckSeconds)
        assertEquals(3600, config.telemetry.quotaPeriodSeconds)
        assertEquals(200, config.telemetry.quotaMaxMeasurements)
        assertEquals(4, config.telemetry.anomalyBreachWindows)
        assertEquals(28_800, config.telemetry.anomalyWindowSeconds)
        assertEquals(900, config.telemetry.quarantineSeconds)
        assertEquals(120, config.capacityMonitoring.intervalSeconds)
        assertEquals(1000, config.capacityMonitoring.measurementsWarnRows)
        assertEquals(2000, config.capacityMonitoring.measurementsWarnBytes)
        assertEquals(3000, config.capacityMonitoring.databaseWarnBytes)
        assertEquals(730, config.telemetryRetention.retentionDays)
        assertEquals(600, config.telemetryRetention.intervalSeconds)
        assertEquals(2500, config.telemetryRetention.batchSize)
        assertEquals(5, config.telemetryRetention.maxBatchesPerRun)
        assertEquals(RuntimeEnvironment.DEVELOPMENT, config.deployment.environment)
    }

    @Test
    fun failsWithoutRequiredSecrets() {
        val environment = requiredEnvironment() - "DATABASE_PASSWORD"

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun readsRuntimeDatabasePasswordFromBoundedFileAndRejectsAmbiguousSources() {
        val passwordFile = Files.createTempFile("jaiqal-runtime-password-", ".secret")
        try {
            Files.writeString(passwordFile, "file-backed-secret\n")
            val fileEnvironment = (requiredEnvironment() - "DATABASE_PASSWORD") +
                ("DATABASE_PASSWORD_FILE" to passwordFile.toAbsolutePath().toString())

            assertEquals("file-backed-secret", AppConfig.fromEnvironment(fileEnvironment::get).database.password)
            assertFailsWith<IllegalStateException> {
                AppConfig.fromEnvironment(
                    (requiredEnvironment() + ("DATABASE_PASSWORD_FILE" to passwordFile.toString()))::get,
                )
            }
        } finally {
            Files.deleteIfExists(passwordFile)
        }
    }

    @Test
    fun rejectsOversizedOrRelativeRuntimeSecretFiles() {
        val oversized = Files.createTempFile("jaiqal-oversized-password-", ".secret")
        try {
            Files.writeString(oversized, "x".repeat(4_097))
            val withoutInlinePassword = requiredEnvironment() - "DATABASE_PASSWORD"
            assertFailsWith<IllegalStateException> {
                AppConfig.fromEnvironment(
                    (withoutInlinePassword + ("DATABASE_PASSWORD_FILE" to oversized.toString()))::get,
                )
            }
            assertFailsWith<IllegalStateException> {
                AppConfig.fromEnvironment(
                    (withoutInlinePassword + ("DATABASE_PASSWORD_FILE" to "relative.secret"))::get,
                )
            }
        } finally {
            Files.deleteIfExists(oversized)
        }
    }

    @Test
    fun readsSeparateMigrationCredentialsWhenConfigured() {
        val environment = requiredEnvironment() + mapOf(
            "MIGRATION_DATABASE_URL" to "jdbc:postgresql://migration-db:5432/jaiqal",
            "MIGRATION_DATABASE_USER" to "jaiqal_migrator",
            "MIGRATION_DATABASE_PASSWORD" to "migration-secret",
        )

        val database = MigrationDatabaseConfig.fromEnvironment(environment::get)

        assertEquals("jaiqal_migrator", database.user)
        assertEquals("jdbc:postgresql://migration-db:5432/jaiqal", database.url)
    }

    @Test
    fun rejectsPartialMigrationCredentials() {
        val environment = requiredEnvironment() + mapOf(
            "MIGRATION_DATABASE_USER" to "jaiqal_migrator",
            "MIGRATION_DATABASE_PASSWORD" to "migration-secret",
        )

        assertFailsWith<IllegalStateException> {
            MigrationDatabaseConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun rejectsCredentialsEmbeddedInDatabaseUrls() {
        val runtimeCredentialsInUrl = requiredEnvironment() +
            ("DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal?user=admin")
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(runtimeCredentialsInUrl::get)
        }

        val migrationCredentialsInUrl = requiredEnvironment() + mapOf(
            "MIGRATION_DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal?password=secret",
            "MIGRATION_DATABASE_USER" to "jaiqal_migrator",
            "MIGRATION_DATABASE_PASSWORD" to "migration-secret",
        )
        assertFailsWith<IllegalArgumentException> {
            MigrationDatabaseConfig.fromEnvironment(migrationCredentialsInUrl::get)
        }
    }

    @Test
    fun rejectsMigrationRoleMatchingRuntimeRole() {
        val environment = requiredEnvironment() + mapOf(
            "MIGRATION_DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal",
            "MIGRATION_DATABASE_USER" to "JAIQAL",
            "MIGRATION_DATABASE_PASSWORD" to "migration-secret",
        )

        assertFailsWith<IllegalArgumentException> {
            MigrationDatabaseConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun migrationTlsCheckRequiresHostnameVerificationAndChannelBinding() {
        val secure = MigrationDatabaseConfig(
            "jdbc:postgresql://db.example.test:5432/jaiqal?sslmode=verify-full&channelBinding=require",
            "jaiqal_migrator",
            "migration-secret",
        )
        val missingChannelBinding = MigrationDatabaseConfig(
            "jdbc:postgresql://db.example.test:5432/jaiqal?sslmode=verify-full",
            "jaiqal_migrator",
            "migration-secret",
        )

        assertEquals(true, secure.usesVerifiedTls())
        assertEquals(false, missingChannelBinding.usesVerifiedTls())
    }

    @Test
    fun rejectsInvalidPort() {
        val environment = requiredEnvironment() + ("HTTP_PORT" to "not-a-number")

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun failsWithoutFirebaseProjectId() {
        val environment = requiredEnvironment() - "FIREBASE_PROJECT_ID"

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun readsStrictFirebaseRevocationFlag() {
        val enabled = requiredEnvironment() + ("FIREBASE_CHECK_REVOKED_TOKENS" to "true")
        assertEquals(true, AppConfig.fromEnvironment(enabled::get).firebase.checkRevokedTokens)

        val invalid = requiredEnvironment() + ("FIREBASE_CHECK_REVOKED_TOKENS" to "yes")
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(invalid::get)
        }
    }

    @Test
    fun readsStrictFirebaseAutoProvisioningFlag() {
        val disabled = requiredEnvironment() + ("FIREBASE_AUTO_PROVISION_USERS" to "false")
        assertEquals(false, AppConfig.fromEnvironment(disabled::get).firebase.autoProvisionUsers)

        val invalid = requiredEnvironment() + ("FIREBASE_AUTO_PROVISION_USERS" to "yes")
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(invalid::get)
        }
    }

    @Test
    fun rejectsInvalidHttpLimits() {
        val invalidNumber = requiredEnvironment() + ("HTTP_MAX_BODY_BYTES" to "large")
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(invalidNumber::get)
        }

        val invalidRelationship = requiredEnvironment() + mapOf(
            "HTTP_MAX_BODY_BYTES" to "1024",
            "TELEMETRY_BATCH_MAX_BODY_BYTES" to "512",
        )
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(invalidRelationship::get)
        }
    }

    @Test
    fun rejectsQuotaSmallerThanOneMaximumBatch() {
        val environment = requiredEnvironment() + ("TELEMETRY_DEVICE_QUOTA_MAX_MEASUREMENTS" to "99")

        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun rejectsAnomalySettingsThatPermitBurstQuarantineOrPermanentLockout() {
        listOf(
            mapOf("TELEMETRY_ANOMALY_BREACH_WINDOWS" to "2"),
            mapOf(
                "TELEMETRY_DEVICE_QUOTA_PERIOD_SECONDS" to "3600",
                "TELEMETRY_ANOMALY_BREACH_WINDOWS" to "3",
                "TELEMETRY_ANOMALY_WINDOW_SECONDS" to "10000",
            ),
            mapOf("TELEMETRY_QUARANTINE_SECONDS" to "299"),
            mapOf("TELEMETRY_QUARANTINE_SECONDS" to "604801"),
        ).forEach { unsafe ->
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnvironment((requiredEnvironment() + unsafe)::get)
            }
        }
    }

    @Test
    fun rejectsRetentionShorterThanHistoryWindowAndUnsafeWorkerBounds() {
        val shorterThanHistory = requiredEnvironment() + mapOf(
            "TELEMETRY_RETENTION_DAYS" to "30",
            "HISTORY_MAX_RANGE_SECONDS" to "2678400",
        )
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(shorterThanHistory::get)
        }

        listOf(
            "TELEMETRY_RETENTION_INTERVAL_SECONDS" to "59",
            "TELEMETRY_RETENTION_BATCH_SIZE" to "100001",
            "TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN" to "101",
        ).forEach { (name, value) ->
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnvironment((requiredEnvironment() + (name to value))::get)
            }
        }
    }

    @Test
    fun rejectsUnsafeSseLifetimeConfiguration() {
        val tooLong = requiredEnvironment() + ("SSE_MAX_LIFETIME_SECONDS" to "3601")
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(tooLong::get)
        }

        val recheckAfterLifetime = requiredEnvironment() + mapOf(
            "SSE_MAX_LIFETIME_SECONDS" to "30",
            "SSE_OWNERSHIP_RECHECK_SECONDS" to "31",
        )
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(recheckAfterLifetime::get)
        }
    }

    @Test
    fun rejectsReadinessCacheTtlOutsideShortBoundedRange() {
        listOf("0", "not-a-number").forEach { value ->
            assertFailsWith<IllegalStateException> {
                AppConfig.fromEnvironment(
                    (requiredEnvironment() + ("READINESS_CACHE_TTL_MILLISECONDS" to value))::get,
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                (requiredEnvironment() + ("READINESS_CACHE_TTL_MILLISECONDS" to "5001"))::get,
            )
        }
    }

    @Test
    fun acceptsOnlyFailClosedProductionSecurityConfiguration() {
        val config = AppConfig.fromEnvironment(productionEnvironment()::get)

        assertEquals(RuntimeEnvironment.PRODUCTION, config.deployment.environment)
        assertEquals("0123456789abcdef0123456789abcdef01234567", config.deployment.commitSha)
        assertEquals("https://api.example.test", config.deployment.publicApiUrl)
        assertEquals(true, config.deployment.trustedProxyTerminatesTls)
        assertEquals(true, config.deployment.isTrustedProxyPeer("10.42.7.9"))
        assertEquals(true, config.deployment.isTrustedProxyPeer("2001:db8:42::1234"))
        assertEquals(false, config.deployment.isTrustedProxyPeer("10.43.0.1"))
        assertEquals(false, config.deployment.isTrustedProxyPeer("2001:db8:43::1"))
        assertEquals(false, config.deployment.isTrustedProxyPeer("ingress.example.test"))
        assertEquals(true, config.firebase.checkRevokedTokens)
    }

    @Test
    fun rejectsUnsafeProductionSecurityConfiguration() {
        val valid = productionEnvironment()
        val unsafeConfigurations = listOf(
            valid - "FIREBASE_CHECK_REVOKED_TOKENS",
            valid - "DEPLOYMENT_COMMIT_SHA",
            valid + ("DEPLOYMENT_COMMIT_SHA" to "0123456"),
            valid + ("DEPLOYMENT_COMMIT_SHA" to "0123456789ABCDEF0123456789ABCDEF01234567"),
            valid + ("FIREBASE_CHECK_REVOKED_TOKENS" to "false"),
            valid + ("DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal?sslmode=require&channelBinding=require"),
            valid + ("DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal?sslmode=verify-full"),
            valid + ("PUBLIC_API_URL" to "http://api.example.test"),
            valid + ("TRUSTED_PROXY_TERMINATES_TLS" to "false"),
            valid - "TRUSTED_PROXY_CIDRS",
            valid + ("TRUSTED_PROXY_CIDRS" to "ingress.example.test/24"),
            valid + ("TRUSTED_PROXY_CIDRS" to "10.42.0.1/16"),
            valid + ("TRUSTED_PROXY_CIDRS" to "10.42.0.0/33"),
            valid + ("TRUSTED_PROXY_CIDRS" to "0.0.0.0/0"),
            valid + ("TRUSTED_PROXY_CIDRS" to "::/0"),
            valid + ("TRUSTED_PROXY_CIDRS" to "2001:db8:42::1/64"),
            valid + ("ALLOWED_ORIGINS" to "http://app.example.test"),
            valid + ("ALLOWED_ORIGINS" to "*"),
        )

        unsafeConfigurations.forEach { environment ->
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnvironment(environment::get)
            }
        }

    }

    @Test
    fun rejectsMigrationCredentialsInProductionServerEnvironment() {
        val environment = productionEnvironment() + mapOf(
            "MIGRATION_DATABASE_URL" to "jdbc:postgresql://db.example.test:5432/jaiqal?sslmode=verify-full&channelBinding=require",
            "MIGRATION_DATABASE_USER" to "jaiqal_migrator",
            "MIGRATION_DATABASE_PASSWORD" to "migration-secret",
        )

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun rejectsUnknownRuntimeEnvironment() {
        val environment = requiredEnvironment() + ("APP_ENVIRONMENT" to "staging")

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    private fun requiredEnvironment() = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal",
        "DATABASE_USER" to "jaiqal",
        "DATABASE_PASSWORD" to "database-secret",
        "FIREBASE_PROJECT_ID" to "jaiqal-test",
    )

    private fun productionEnvironment() = requiredEnvironment() + mapOf(
        "APP_ENVIRONMENT" to "production",
        "DEPLOYMENT_COMMIT_SHA" to "0123456789abcdef0123456789abcdef01234567",
        "PUBLIC_API_URL" to "https://api.example.test",
        "TRUSTED_PROXY_TERMINATES_TLS" to "true",
        "TRUSTED_PROXY_CIDRS" to "10.42.0.0/16,2001:db8:42::/64",
        "FIREBASE_CHECK_REVOKED_TOKENS" to "true",
        "DATABASE_URL" to "jdbc:postgresql://db.example.test:5432/jaiqal?sslmode=verify-full&channelBinding=require",
        "ALLOWED_ORIGINS" to "https://app.example.test",
    )
}
