package com.alad1nks.jaiqal.auth

data class DevicePrincipal(
    val deviceId: String,
)

fun interface DeviceTokenAuthenticator {
    suspend fun authenticate(token: String): DevicePrincipal?

    companion object {
        fun rejectAll(): DeviceTokenAuthenticator = DeviceTokenAuthenticator { null }
    }
}
