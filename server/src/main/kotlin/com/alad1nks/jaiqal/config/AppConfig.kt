package com.alad1nks.jaiqal.config

data class AppConfig(
    val httpPort: Int,
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    val allowedOrigins: Set<String>,
    val telemetry: TelemetryConfig = TelemetryConfig(),
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
                jwt = JwtConfig(
                    issuer = required("JWT_ISSUER"),
                    audience = required("JWT_AUDIENCE"),
                    secret = required("JWT_SECRET"),
                    accessTokenSeconds = longValue(environment, "JWT_ACCESS_TOKEN_SECONDS", 900),
                    refreshTokenSeconds = longValue(environment, "JWT_REFRESH_TOKEN_SECONDS", 2_592_000),
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
            )
        }

        private fun longValue(env: (String) -> String?, name: String, default: Long) = env(name)?.toLongOrNull() ?: default
        private fun intValue(env: (String) -> String?, name: String, default: Int) = env(name)?.toIntOrNull() ?: default
        private fun doubleValue(env: (String) -> String?, name: String, default: Double) = env(name)?.toDoubleOrNull() ?: default
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

data class JwtConfig(
    val issuer: String,
    val audience: String,
    val secret: String,
    val accessTokenSeconds: Long = 900,
    val refreshTokenSeconds: Long = 2_592_000,
) {
    init { require(accessTokenSeconds > 0 && refreshTokenSeconds > 0) }
}
