package com.alad1nks.jaiqal.core.push

enum class PushPermissionResult {
    GRANTED,
    DENIED,
    NOT_DETERMINED,
    UNSUPPORTED,
}

/**
 * Boundary for a future authenticated push-token registration flow.
 *
 * The current backend has no user push-token endpoint, so production wiring uses
 * [UnavailablePushTokenRegistrar]. Platform FCM/APNs implementations must not be
 * added until that server contract exists.
 */
interface PushTokenRegistrar {
    suspend fun requestPermission(): PushPermissionResult
    suspend fun currentToken(): String?
    suspend fun syncToken()
}

class UnavailablePushTokenRegistrar : PushTokenRegistrar {
    override suspend fun requestPermission() = PushPermissionResult.UNSUPPORTED
    override suspend fun currentToken(): String? = null
    override suspend fun syncToken() = Unit
}
