package com.alad1nks.jaiqal.config

data class AppConfig(
    val httpPort: Int,
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    val allowedOrigins: Set<String>,
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
                ),
                allowedOrigins = allowedOrigins,
            )
        }
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
)
