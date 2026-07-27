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
        assertEquals("issuer", config.jwt.issuer)
        assertEquals("jaiqal-test", config.firebase.projectId)
        assertEquals(false, config.firebase.checkRevokedTokens)
        assertEquals(true, config.firebase.autoProvisionUsers)
    }

    @Test
    fun failsWithoutRequiredSecrets() {
        val environment = requiredEnvironment() - "JWT_SECRET"

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

    private fun requiredEnvironment() = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/jaiqal",
        "DATABASE_USER" to "jaiqal",
        "DATABASE_PASSWORD" to "database-secret",
        "JWT_ISSUER" to "issuer",
        "JWT_AUDIENCE" to "audience",
        "JWT_SECRET" to "jwt-secret",
        "FIREBASE_PROJECT_ID" to "jaiqal-test",
    )
}
