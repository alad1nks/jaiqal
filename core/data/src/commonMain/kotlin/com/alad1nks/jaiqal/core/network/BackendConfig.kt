package com.alad1nks.jaiqal.core.network

enum class AppEnvironment {
    LOCAL,
    PRODUCTION;

    companion object {
        fun from(value: String): AppEnvironment = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("Unsupported app environment: $value")
    }
}

interface BackendConfig {
    val environment: AppEnvironment
    val baseUrl: String
}

data class DefaultBackendConfig(
    override val environment: AppEnvironment,
    override val baseUrl: String,
) : BackendConfig {
    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "Backend URL must use HTTP or HTTPS"
        }
        require(environment == AppEnvironment.LOCAL || baseUrl.startsWith("https://")) {
            "Production backend URL must use HTTPS"
        }
    }
}
