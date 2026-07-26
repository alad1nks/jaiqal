package com.alad1nks.jaiqal.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun readsAllConfigurationFromEnvironment() {
        val environment = requiredEnvironment() + mapOf(
            "HTTP_PORT" to "9090",
            "ALLOWED_ORIGINS" to "https://app.example.com, http://localhost:8080",
        )

        val config = AppConfig.fromEnvironment(environment::get)

        assertEquals(9090, config.httpPort)
        assertEquals(
            setOf("https://app.example.com", "http://localhost:8080"),
            config.allowedOrigins,
        )
        assertEquals("jdbc:postgresql://db:5432/jaiqal", config.database.url)
        assertEquals("firebase-test-project", config.firebase.projectId)
    }

    @Test
    fun failsWithoutRequiredSecrets() {
        val environment = requiredEnvironment() - "FIREBASE_PROJECT_ID"

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    @Test
    fun rejectsInvalidPort() {
        val environment = requiredEnvironment() + ("HTTP_PORT" to "not-a-number")

        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(environment::get)
        }
    }

    private fun requiredEnvironment() = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal",
        "DATABASE_USER" to "jaiqal",
        "DATABASE_PASSWORD" to "database-secret",
        "FIREBASE_PROJECT_ID" to "firebase-test-project",
    )
}
