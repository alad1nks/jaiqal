package com.alad1nks.jaiqal.config

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

data class AppConfig(
    val httpPort: Int,
    val database: DatabaseConfig,
    val allowedOrigins: Set<String>,
    val firebase: FirebaseConfig,
    val httpLimits: HttpLimitConfig = HttpLimitConfig(),
    val telemetry: TelemetryConfig = TelemetryConfig(),
    val capacityMonitoring: CapacityMonitoringConfig = CapacityMonitoringConfig(),
    val history: HistoryConfig = HistoryConfig(),
    val alerts: AlertConfig = AlertConfig(),
    val deployment: DeploymentConfig = DeploymentConfig(),
    val telemetryRetention: TelemetryRetentionConfig = TelemetryRetentionConfig(),
) {
    init {
        require(telemetryRetention.retentionDays * SECONDS_PER_DAY >= history.maxRangeSeconds) {
            "TELEMETRY_RETENTION_DAYS must cover HISTORY_MAX_RANGE_SECONDS"
        }
        if (deployment.isProduction) {
            require(firebase.checkRevokedTokens) {
                "FIREBASE_CHECK_REVOKED_TOKENS must be true in production"
            }
            require(database.usesVerifiedTls()) {
                "Production DATABASE_URL must set sslmode=verify-full and channelBinding=require"
            }
            require(allowedOrigins.none { origin -> origin == "*" || !origin.startsWith("https://") }) {
                "Production ALLOWED_ORIGINS must contain only explicit https origins"
            }
        }
    }

    companion object {
        fun fromEnvironment(
            environment: (String) -> String? = System::getenv,
        ): AppConfig {
            fun required(name: String): String =
                environment(name)?.trim()?.takeIf(String::isNotEmpty)
                    ?: error("Required environment variable $name is not set")

            val configuredPort = environment("HTTP_PORT")?.trim()?.takeIf(String::isNotEmpty)
            val httpPort = configuredPort?.toIntOrNull() ?: if (configuredPort == null) {
                8080
            } else {
                error("HTTP_PORT must be an integer between 1 and 65535")
            }
            require(httpPort in 1..65535) {
                "HTTP_PORT must be an integer between 1 and 65535"
            }

            val allowedOrigins = environment("ALLOWED_ORIGINS")
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            val runtimeEnvironment = when (val value = environment("APP_ENVIRONMENT")?.trim()?.lowercase()) {
                null, "", "development" -> RuntimeEnvironment.DEVELOPMENT
                "production" -> RuntimeEnvironment.PRODUCTION
                else -> error("APP_ENVIRONMENT must be development or production, but was '$value'")
            }
            if (
                runtimeEnvironment == RuntimeEnvironment.PRODUCTION &&
                MIGRATION_DATABASE_VARIABLES.any { environment(it)?.isNotBlank() == true }
            ) {
                error("Migration database credentials must not be exposed to the production server process")
            }

            return AppConfig(
                httpPort = httpPort,
                database = DatabaseConfig.fromEnvironment(
                    environment = environment,
                ),
                firebase = FirebaseConfig(
                    projectId = required("FIREBASE_PROJECT_ID"),
                    checkRevokedTokens = booleanValue(environment, "FIREBASE_CHECK_REVOKED_TOKENS", false),
                    autoProvisionUsers = booleanValue(environment, "FIREBASE_AUTO_PROVISION_USERS", true),
                ),
                deployment = DeploymentConfig(
                    environment = runtimeEnvironment,
                    commitSha = environment("DEPLOYMENT_COMMIT_SHA")?.trim()?.takeIf(String::isNotEmpty),
                    publicApiUrl = environment("PUBLIC_API_URL")?.trim()?.takeIf(String::isNotEmpty),
                    trustedProxyTerminatesTls = booleanValue(environment, "TRUSTED_PROXY_TERMINATES_TLS", false),
                    trustedProxyCidrs = environment("TRUSTED_PROXY_CIDRS")
                        .orEmpty()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                        .map(TrustedProxyCidr::parse),
                ),
                allowedOrigins = allowedOrigins,
                httpLimits = HttpLimitConfig(
                    maxBodyBytes = positiveLongValue(environment, "HTTP_MAX_BODY_BYTES", 65_536),
                    telemetryBatchMaxBodyBytes = positiveLongValue(environment, "TELEMETRY_BATCH_MAX_BODY_BYTES", 131_072),
                    rateLimitPeriodSeconds = positiveLongValue(environment, "RATE_LIMIT_PERIOD_SECONDS", 60),
                    readinessRequestsPerPeriod = positiveIntValue(environment, "READINESS_RATE_LIMIT_REQUESTS", 30),
                    readinessCacheTtlMilliseconds = positiveLongValue(environment, "READINESS_CACHE_TTL_MILLISECONDS", 1_000),
                    userApiRequestsPerPeriod = positiveIntValue(environment, "USER_API_RATE_LIMIT_REQUESTS", 120),
                    telemetryRequestsPerPeriod = positiveIntValue(environment, "TELEMETRY_RATE_LIMIT_REQUESTS", 120),
                    sseMaxConnectionsPerUser = positiveIntValue(environment, "SSE_MAX_CONNECTIONS_PER_USER", 3),
                    sseMaxConnectionsPerIp = positiveIntValue(environment, "SSE_MAX_CONNECTIONS_PER_IP", 10),
                ),
                telemetry = TelemetryConfig(
                    pastWindowSeconds = longValue(environment, "TELEMETRY_PAST_WINDOW_SECONDS", 2_592_000),
                    futureWindowSeconds = longValue(environment, "TELEMETRY_FUTURE_WINDOW_SECONDS", 300),
                    minTemperatureCelsius = doubleValue(environment, "TELEMETRY_MIN_TEMPERATURE_CELSIUS", -50.0),
                    maxTemperatureCelsius = doubleValue(environment, "TELEMETRY_MAX_TEMPERATURE_CELSIUS", 100.0),
                    minAdc = intValue(environment, "TELEMETRY_MIN_ADC", 0),
                    maxAdc = intValue(environment, "TELEMETRY_MAX_ADC", 65_535),
                    nextUploadSeconds = intValue(environment, "TELEMETRY_NEXT_UPLOAD_SECONDS", 60),
                    quotaPeriodSeconds = positiveLongValue(environment, "TELEMETRY_DEVICE_QUOTA_PERIOD_SECONDS", 86_400),
                    quotaMaxMeasurements = positiveIntValue(environment, "TELEMETRY_DEVICE_QUOTA_MAX_MEASUREMENTS", 1_440),
                    anomalyBreachWindows = positiveIntValue(environment, "TELEMETRY_ANOMALY_BREACH_WINDOWS", 3),
                    anomalyWindowSeconds = positiveLongValue(environment, "TELEMETRY_ANOMALY_WINDOW_SECONDS", 604_800),
                    quarantineSeconds = positiveLongValue(environment, "TELEMETRY_QUARANTINE_SECONDS", 3_600),
                ),
                capacityMonitoring = CapacityMonitoringConfig(
                    intervalSeconds = positiveLongValue(environment, "CAPACITY_MONITOR_INTERVAL_SECONDS", 300),
                    measurementsWarnRows = positiveLongValue(environment, "CAPACITY_MEASUREMENTS_WARN_ROWS", 10_000_000),
                    measurementsWarnBytes = positiveLongValue(environment, "CAPACITY_MEASUREMENTS_WARN_BYTES", 5_368_709_120),
                    databaseWarnBytes = positiveLongValue(environment, "CAPACITY_DATABASE_WARN_BYTES", 10_737_418_240),
                ),
                history = HistoryConfig(
                    maxRangeSeconds = longValue(environment, "HISTORY_MAX_RANGE_SECONDS", 31_536_000),
                    defaultRangeSeconds = longValue(environment, "HISTORY_DEFAULT_RANGE_SECONDS", 86_400),
                    maxPoints = intValue(environment, "HISTORY_MAX_POINTS", 2_000),
                    onlineWindowSeconds = longValue(environment, "DEVICE_ONLINE_WINDOW_SECONDS", 180),
                    heartbeatSeconds = longValue(environment, "SSE_HEARTBEAT_SECONDS", 15),
                    streamMaxLifetimeSeconds = positiveLongValue(environment, "SSE_MAX_LIFETIME_SECONDS", 300),
                    streamOwnershipRecheckSeconds = positiveLongValue(environment, "SSE_OWNERSHIP_RECHECK_SECONDS", 30),
                ),
                alerts = AlertConfig(
                    evaluationSeconds = longValue(environment, "ALERT_EVALUATION_SECONDS", 30),
                    outboxPollSeconds = longValue(environment, "NOTIFICATION_OUTBOX_POLL_SECONDS", 5),
                    outboxBatchSize = intValue(environment, "NOTIFICATION_OUTBOX_BATCH_SIZE", 25),
                    outboxMaxBackoffSeconds = longValue(environment, "NOTIFICATION_OUTBOX_MAX_BACKOFF_SECONDS", 3_600),
                ),
                telemetryRetention = TelemetryRetentionConfig(
                    retentionDays = positiveLongValue(environment, "TELEMETRY_RETENTION_DAYS", 365),
                    intervalSeconds = positiveLongValue(environment, "TELEMETRY_RETENTION_INTERVAL_SECONDS", 3_600),
                    batchSize = positiveIntValue(environment, "TELEMETRY_RETENTION_BATCH_SIZE", 5_000),
                    maxBatchesPerRun = positiveIntValue(environment, "TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN", 20),
                ),
            )
        }

        private fun longValue(env: (String) -> String?, name: String, default: Long) = env(name)?.toLongOrNull() ?: default
        private fun intValue(env: (String) -> String?, name: String, default: Int) = env(name)?.toIntOrNull() ?: default
        private fun doubleValue(env: (String) -> String?, name: String, default: Double) = env(name)?.toDoubleOrNull() ?: default
        private fun booleanValue(env: (String) -> String?, name: String, default: Boolean): Boolean =
            when (val value = env(name)?.trim()?.lowercase()) {
                null, "" -> default
                "true" -> true
                "false" -> false
                else -> error("$name must be true or false, but was '$value'")
            }

        private fun positiveLongValue(env: (String) -> String?, name: String, default: Long): Long {
            val configured = env(name)?.trim()?.takeIf(String::isNotEmpty) ?: return default
            return configured.toLongOrNull()?.takeIf { it > 0 }
                ?: error("$name must be a positive integer")
        }

        private fun positiveIntValue(env: (String) -> String?, name: String, default: Int): Int {
            val configured = env(name)?.trim()?.takeIf(String::isNotEmpty) ?: return default
            return configured.toIntOrNull()?.takeIf { it > 0 }
                ?: error("$name must be a positive integer")
        }

        private val MIGRATION_DATABASE_VARIABLES = listOf(
            "MIGRATION_DATABASE_URL",
            "MIGRATION_DATABASE_USER",
            "MIGRATION_DATABASE_PASSWORD",
            "MIGRATION_DATABASE_PASSWORD_FILE",
        )
        private const val SECONDS_PER_DAY = 86_400L
    }
}

enum class RuntimeEnvironment { DEVELOPMENT, PRODUCTION }

data class DeploymentConfig(
    val environment: RuntimeEnvironment = RuntimeEnvironment.DEVELOPMENT,
    val commitSha: String? = null,
    val publicApiUrl: String? = null,
    val trustedProxyTerminatesTls: Boolean = false,
    val trustedProxyCidrs: List<TrustedProxyCidr> = emptyList(),
) {
    val isProduction: Boolean get() = environment == RuntimeEnvironment.PRODUCTION

    fun isTrustedProxyPeer(address: String): Boolean =
        trustedProxyCidrs.any { network -> network.containsLiteral(address) }

    init {
        commitSha?.let {
            require(it.matches(Regex("[0-9a-f]{40}"))) {
                "DEPLOYMENT_COMMIT_SHA must be a full lowercase Git commit SHA"
            }
        }
        if (isProduction) {
            requireNotNull(commitSha) { "DEPLOYMENT_COMMIT_SHA is required in production" }
            val configuredUrl = requireNotNull(publicApiUrl) { "PUBLIC_API_URL is required in production" }
            val uri = runCatching { URI(configuredUrl) }
                .getOrElse { throw IllegalArgumentException("PUBLIC_API_URL must be a valid https origin") }
            require(
                uri.scheme.equals("https", ignoreCase = true) &&
                    uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    (uri.path.isNullOrEmpty() || uri.path == "/"),
            ) { "PUBLIC_API_URL must be an https origin without credentials, path, query, or fragment" }
            require(trustedProxyTerminatesTls) {
                "TRUSTED_PROXY_TERMINATES_TLS must be true in production"
            }
            require(trustedProxyCidrs.isNotEmpty()) {
                "TRUSTED_PROXY_CIDRS must contain at least one ingress network in production"
            }
        }
    }
}

data class HttpLimitConfig(
    val maxBodyBytes: Long = 65_536,
    val telemetryBatchMaxBodyBytes: Long = 131_072,
    val rateLimitPeriodSeconds: Long = 60,
    val readinessRequestsPerPeriod: Int = 30,
    val readinessCacheTtlMilliseconds: Long = 1_000,
    val userApiRequestsPerPeriod: Int = 120,
    val telemetryRequestsPerPeriod: Int = 120,
    val sseMaxConnectionsPerUser: Int = 3,
    val sseMaxConnectionsPerIp: Int = 10,
) {
    init {
        require(maxBodyBytes > 0)
        require(telemetryBatchMaxBodyBytes >= maxBodyBytes)
        require(rateLimitPeriodSeconds > 0)
        require(readinessRequestsPerPeriod > 0)
        require(readinessCacheTtlMilliseconds in 1..5_000) {
            "READINESS_CACHE_TTL_MILLISECONDS must be between 1 and 5000"
        }
        require(userApiRequestsPerPeriod > 0)
        require(telemetryRequestsPerPeriod > 0)
        require(sseMaxConnectionsPerUser > 0)
        require(sseMaxConnectionsPerIp > 0)
    }
}

data class FirebaseConfig(
    val projectId: String,
    val checkRevokedTokens: Boolean = false,
    val autoProvisionUsers: Boolean = true,
) {
    init {
        require(projectId.isNotBlank()) { "FIREBASE_PROJECT_ID must not be blank" }
    }
}

data class AlertConfig(
    val evaluationSeconds: Long = 30,
    val outboxPollSeconds: Long = 5,
    val outboxBatchSize: Int = 25,
    val outboxMaxBackoffSeconds: Long = 3_600,
) {
    init { require(evaluationSeconds > 0 && outboxPollSeconds > 0 && outboxBatchSize in 1..100 && outboxMaxBackoffSeconds > 0) }
}

data class HistoryConfig(
    val maxRangeSeconds: Long = 31_536_000,
    val defaultRangeSeconds: Long = 86_400,
    val maxPoints: Int = 2_000,
    val onlineWindowSeconds: Long = 180,
    val heartbeatSeconds: Long = 15,
    val streamMaxLifetimeSeconds: Long = 300,
    val streamOwnershipRecheckSeconds: Long = 30,
) {
    init {
        require(
            maxRangeSeconds > 0 &&
                defaultRangeSeconds in 1..maxRangeSeconds &&
                maxPoints > 0 &&
                onlineWindowSeconds > 0 &&
                heartbeatSeconds > 0 &&
                streamMaxLifetimeSeconds in 1..3_600 &&
                streamOwnershipRecheckSeconds in 1..streamMaxLifetimeSeconds,
        )
    }
}

data class TelemetryConfig(
    val pastWindowSeconds: Long = 2_592_000,
    val futureWindowSeconds: Long = 300,
    val minTemperatureCelsius: Double = -50.0,
    val maxTemperatureCelsius: Double = 100.0,
    val minAdc: Int = 0,
    val maxAdc: Int = 65_535,
    val nextUploadSeconds: Int = 60,
    val quotaPeriodSeconds: Long = 86_400,
    val quotaMaxMeasurements: Int = 1_440,
    val anomalyBreachWindows: Int = 3,
    val anomalyWindowSeconds: Long = 604_800,
    val quarantineSeconds: Long = 3_600,
) {
    init {
        require(pastWindowSeconds >= 0 && futureWindowSeconds >= 0)
        require(minTemperatureCelsius.isFinite() && minTemperatureCelsius <= maxTemperatureCelsius)
        require(minAdc <= maxAdc)
        require(nextUploadSeconds > 0)
        require(quotaPeriodSeconds > 0)
        require(quotaMaxMeasurements >= 100) { "Per-device quota must allow one maximum-size telemetry batch" }
        require(anomalyBreachWindows >= 3) { "Device quarantine requires at least three breached quota windows" }
        require(anomalyWindowSeconds / anomalyBreachWindows >= quotaPeriodSeconds) {
            "Anomaly observation window must cover every required quota window"
        }
        require(quarantineSeconds in 300..604_800) { "Device quarantine must be temporary (5 minutes to 7 days)" }
    }
}

data class CapacityMonitoringConfig(
    val intervalSeconds: Long = 300,
    val measurementsWarnRows: Long = 10_000_000,
    val measurementsWarnBytes: Long = 5_368_709_120,
    val databaseWarnBytes: Long = 10_737_418_240,
) {
    init {
        require(intervalSeconds > 0)
        require(measurementsWarnRows > 0)
        require(measurementsWarnBytes > 0)
        require(databaseWarnBytes > 0)
    }
}

data class TelemetryRetentionConfig(
    val retentionDays: Long = 365,
    val intervalSeconds: Long = 3_600,
    val batchSize: Int = 5_000,
    val maxBatchesPerRun: Int = 20,
) {
    init {
        require(retentionDays in 1..3_650) { "TELEMETRY_RETENTION_DAYS must be between 1 and 3650" }
        require(intervalSeconds in 60..86_400) {
            "TELEMETRY_RETENTION_INTERVAL_SECONDS must be between 60 and 86400"
        }
        require(batchSize in 1..100_000) {
            "TELEMETRY_RETENTION_BATCH_SIZE must be between 1 and 100000"
        }
        require(maxBatchesPerRun in 1..100) {
            "TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN must be between 1 and 100"
        }
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    init {
        require(url.isNotBlank() && user.isNotBlank() && password.isNotBlank()) {
            "Runtime database URL, user, and password must not be blank"
        }
        require(!url.hasCredentialProperties()) {
            "DATABASE_URL must not contain user or password properties"
        }
    }

    internal fun usesVerifiedTls() = url.usesVerifiedTls()

    companion object {
        fun fromEnvironment(
            environment: (String) -> String? = System::getenv,
        ): DatabaseConfig {
            fun required(name: String): String =
                environment(name)?.trim()?.takeIf(String::isNotEmpty)
                    ?: error("Required environment variable $name is not set")

            return DatabaseConfig(
                url = required("DATABASE_URL"),
                user = required("DATABASE_USER"),
                password = secretValue(environment, "DATABASE_PASSWORD", "DATABASE_PASSWORD_FILE"),
            )
        }
    }
}

class MigrationDatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    init {
        require(url.isNotBlank() && user.isNotBlank() && password.isNotBlank()) {
            "Migration database URL, user, and password must not be blank"
        }
        require(!url.hasCredentialProperties()) {
            "MIGRATION_DATABASE_URL must not contain user or password properties"
        }
    }

    internal fun usesVerifiedTls() = url.usesVerifiedTls()

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): MigrationDatabaseConfig {
            fun required(name: String): String =
                environment(name)?.trim()?.takeIf(String::isNotEmpty)
                    ?: error("Required environment variable $name is not set")

            val config = MigrationDatabaseConfig(
                url = required("MIGRATION_DATABASE_URL"),
                user = required("MIGRATION_DATABASE_USER"),
                password = secretValue(
                    environment,
                    "MIGRATION_DATABASE_PASSWORD",
                    "MIGRATION_DATABASE_PASSWORD_FILE",
                ),
            )
            environment("DATABASE_USER")?.trim()?.takeIf(String::isNotEmpty)?.let { runtimeUser ->
                require(!config.user.equals(runtimeUser, ignoreCase = true)) {
                    "MIGRATION_DATABASE_USER must be distinct from DATABASE_USER"
                }
            }
            return config
        }

        fun fromEnvironmentOrRuntime(
            runtime: DatabaseConfig,
            environment: (String) -> String? = System::getenv,
        ): MigrationDatabaseConfig {
            val names = listOf(
                "MIGRATION_DATABASE_URL",
                "MIGRATION_DATABASE_USER",
                "MIGRATION_DATABASE_PASSWORD",
                "MIGRATION_DATABASE_PASSWORD_FILE",
            )
            return if (names.any { environment(it)?.isNotBlank() == true }) {
                fromEnvironment(environment)
            } else {
                MigrationDatabaseConfig(runtime.url, runtime.user, runtime.password)
            }
        }
    }
}

private fun String.databaseProperties(): Map<String, String> =
    substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8).lowercase()
            val value = URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8)
            key to value
        }
        .toMap()

private fun String.usesVerifiedTls(): Boolean {
    val properties = databaseProperties()
    return properties["sslmode"].equals("verify-full", ignoreCase = true) &&
        properties["channelbinding"].equals("require", ignoreCase = true)
}

private fun String.hasCredentialProperties(): Boolean {
    val properties = databaseProperties()
    return "user" in properties || "password" in properties
}

private fun secretValue(
    environment: (String) -> String?,
    valueName: String,
    fileName: String,
): String {
    val inline = environment(valueName)?.trim()?.takeIf(String::isNotEmpty)
    val configuredPath = environment(fileName)?.trim()?.takeIf(String::isNotEmpty)
    check(inline == null || configuredPath == null) { "$valueName and $fileName must not both be set" }
    if (inline != null) return inline
    val pathValue = configuredPath ?: error("Required environment variable $valueName or $fileName is not set")
    val path = runCatching { Path.of(pathValue) }
        .getOrElse { throw IllegalStateException("$fileName must be a valid absolute path") }
    check(path.isAbsolute) { "$fileName must be an absolute path" }
    check(Files.isRegularFile(path)) {
        "$fileName must reference a readable regular file"
    }
    val bytes = runCatching {
        Files.newInputStream(path).use { input -> input.readNBytes(MAX_SECRET_FILE_BYTES.toInt() + 1) }
    }.getOrElse { throw IllegalStateException("$fileName must reference a readable regular file") }
    check(bytes.size.toLong() in 1..MAX_SECRET_FILE_BYTES) {
        "$fileName must reference a readable regular file no larger than $MAX_SECRET_FILE_BYTES bytes"
    }
    return runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .trim()
    }
        .getOrElse { throw IllegalStateException("$fileName must reference a readable UTF-8 file") }
        .takeIf(String::isNotEmpty)
        ?: error("$fileName must not be empty")
}

private const val MAX_SECRET_FILE_BYTES = 4_096L
