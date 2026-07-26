package com.alad1nks.jaiqal.config

data class AppConfig(
    val httpPort: Int,
    val database: DatabaseConfig,
    val firebase: FirebaseConfig,
    val allowedOrigins: Set<String>,
    val telemetry: TelemetryConfig = TelemetryConfig(),
    val history: HistoryConfig = HistoryConfig(),
    val alerts: AlertConfig = AlertConfig(),
) {
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

            return AppConfig(
                httpPort = httpPort,
                database = DatabaseConfig(
                    url = required("DATABASE_URL"),
                    user = required("DATABASE_USER"),
                    password = required("DATABASE_PASSWORD"),
                ),
                firebase = FirebaseConfig(
                    projectId = required("FIREBASE_PROJECT_ID"),
                    autoProvisionUsers = booleanValue(environment, "FIREBASE_AUTO_PROVISION_USERS", false),
                    checkRevokedTokens = booleanValue(environment, "FIREBASE_CHECK_REVOKED_TOKENS", true),
                ),
                allowedOrigins = allowedOrigins,
                telemetry = TelemetryConfig(
                    pastWindowSeconds = longValue(environment, "TELEMETRY_PAST_WINDOW_SECONDS", 2_592_000),
                    futureWindowSeconds = longValue(environment, "TELEMETRY_FUTURE_WINDOW_SECONDS", 300),
                    minTemperatureCelsius = doubleValue(environment, "TELEMETRY_MIN_TEMPERATURE_CELSIUS", -50.0),
                    maxTemperatureCelsius = doubleValue(environment, "TELEMETRY_MAX_TEMPERATURE_CELSIUS", 100.0),
                    minAdc = intValue(environment, "TELEMETRY_MIN_ADC", 0),
                    maxAdc = intValue(environment, "TELEMETRY_MAX_ADC", 65_535),
                    nextUploadSeconds = intValue(environment, "TELEMETRY_NEXT_UPLOAD_SECONDS", 60),
                ),
                history = HistoryConfig(
                    maxRangeSeconds = longValue(environment, "HISTORY_MAX_RANGE_SECONDS", 31_536_000),
                    defaultRangeSeconds = longValue(environment, "HISTORY_DEFAULT_RANGE_SECONDS", 86_400),
                    maxPoints = intValue(environment, "HISTORY_MAX_POINTS", 2_000),
                    onlineWindowSeconds = longValue(environment, "DEVICE_ONLINE_WINDOW_SECONDS", 180),
                    heartbeatSeconds = longValue(environment, "SSE_HEARTBEAT_SECONDS", 15),
                ),
                alerts = AlertConfig(
                    evaluationSeconds = longValue(environment, "ALERT_EVALUATION_SECONDS", 30),
                    outboxPollSeconds = longValue(environment, "NOTIFICATION_OUTBOX_POLL_SECONDS", 5),
                    outboxBatchSize = intValue(environment, "NOTIFICATION_OUTBOX_BATCH_SIZE", 25),
                    outboxMaxBackoffSeconds = longValue(environment, "NOTIFICATION_OUTBOX_MAX_BACKOFF_SECONDS", 3_600),
                ),
            )
        }

        private fun longValue(env: (String) -> String?, name: String, default: Long) = env(name)?.toLongOrNull() ?: default
        private fun intValue(env: (String) -> String?, name: String, default: Int) = env(name)?.toIntOrNull() ?: default
        private fun doubleValue(env: (String) -> String?, name: String, default: Double) = env(name)?.toDoubleOrNull() ?: default
        private fun booleanValue(env: (String) -> String?, name: String, default: Boolean): Boolean =
            env(name)?.trim()?.lowercase()?.let { value ->
                when (value) { "true" -> true; "false" -> false; else -> error("$name must be true or false") }
            } ?: default
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
) {
    init { require(maxRangeSeconds > 0 && defaultRangeSeconds in 1..maxRangeSeconds && maxPoints > 0 && onlineWindowSeconds > 0 && heartbeatSeconds > 0) }
}

data class TelemetryConfig(
    val pastWindowSeconds: Long = 2_592_000,
    val futureWindowSeconds: Long = 300,
    val minTemperatureCelsius: Double = -50.0,
    val maxTemperatureCelsius: Double = 100.0,
    val minAdc: Int = 0,
    val maxAdc: Int = 65_535,
    val nextUploadSeconds: Int = 60,
) {
    init {
        require(pastWindowSeconds >= 0 && futureWindowSeconds >= 0)
        require(minTemperatureCelsius.isFinite() && minTemperatureCelsius <= maxTemperatureCelsius)
        require(minAdc <= maxAdc)
        require(nextUploadSeconds > 0)
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

data class FirebaseConfig(
    val projectId: String,
    val autoProvisionUsers: Boolean = false,
    val checkRevokedTokens: Boolean = true,
) { init { require(projectId.isNotBlank()) { "FIREBASE_PROJECT_ID must not be blank" } } }
