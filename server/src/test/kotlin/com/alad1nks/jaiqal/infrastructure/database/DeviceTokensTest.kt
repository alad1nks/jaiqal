package com.alad1nks.jaiqal.infrastructure.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceTokensTest {
    @Test
    fun `device token hash is canonical lowercase SHA-256 hex`() {
        val hash = DeviceTokens.hashHex("device-token")

        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(hash, DeviceTokens.hashHex("device-token"))
    }
}
