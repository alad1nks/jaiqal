package com.alad1nks.jaiqal.core.network

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Backend(
        val statusCode: Int,
        val errorCode: String,
        val requestId: String?,
    ) : ApiException("Backend request failed ($errorCode)")

    class SessionExpired : ApiException("The Firebase session must be authenticated again")

    class Connectivity(cause: Throwable) : ApiException("The backend is unreachable", cause)

    class Timeout(cause: Throwable) : ApiException("The backend request timed out", cause)

    class InvalidResponse(cause: Throwable) : ApiException("The backend returned an invalid response", cause)
}
