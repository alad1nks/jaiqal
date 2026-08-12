package com.alad1nks.jaiqal.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendConfigTest {
    @Test
    fun localEnvironmentAllowsEmulatorHttp() {
        val config = DefaultBackendConfig(AppEnvironment.LOCAL, "http://10.0.2.2:8080")
        assertEquals("http://10.0.2.2:8080", config.baseUrl)
    }

    @Test
    fun productionEnvironmentRequiresHttps() {
        assertFailsWith<IllegalArgumentException> {
            DefaultBackendConfig(AppEnvironment.PRODUCTION, "http://api.example.invalid")
        }
    }
}
