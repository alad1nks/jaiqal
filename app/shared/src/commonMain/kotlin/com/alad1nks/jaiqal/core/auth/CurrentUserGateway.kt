package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.http.HttpMethod

fun interface CurrentUserGateway {
    suspend fun fetchCurrentUser(): CurrentUserResponse
}

class ApiCurrentUserGateway(
    private val apiClient: ApiClient,
) : CurrentUserGateway {
    override suspend fun fetchCurrentUser(): CurrentUserResponse = apiClient.request(
        path = "/api/v1/auth/me",
        serializer = CurrentUserResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Get
    }
}

class UnavailableCurrentUserGateway : CurrentUserGateway {
    override suspend fun fetchCurrentUser(): CurrentUserResponse =
        error("Backend synchronization is unavailable on this platform")
}
