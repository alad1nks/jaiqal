package com.alad1nks.jaiqal.auth

import java.util.UUID
import java.time.Instant

data class DevicePrincipal(
    val deviceId: UUID,
    val disabled: Boolean = false,
    val quarantinedUntil: Instant? = null,
)

fun interface DeviceTokenAuthenticator {
    suspend fun authenticate(token: String): DevicePrincipal?

    companion object {
        fun rejectAll(): DeviceTokenAuthenticator = DeviceTokenAuthenticator { null }
    }
}
