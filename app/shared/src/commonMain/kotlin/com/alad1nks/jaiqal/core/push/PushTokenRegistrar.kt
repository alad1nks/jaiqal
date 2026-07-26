package com.alad1nks.jaiqal.core.push

enum class PushPermissionResult { GRANTED, DENIED, NOT_DETERMINED }

/** Platform contract only: the backend currently has no user push-token endpoint. */
interface PushTokenRegistrar {
    suspend fun requestPermission(): PushPermissionResult
    suspend fun currentToken(): String?
    suspend fun syncToken()
}
