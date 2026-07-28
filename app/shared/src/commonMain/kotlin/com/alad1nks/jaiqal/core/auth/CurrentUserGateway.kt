package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import com.alad1nks.jaiqal.core.network.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun interface CurrentUserGateway {
    suspend fun fetchCurrentUser(idToken: String): CurrentUserResponse
}

class KtorCurrentUserGateway(
    private val client: HttpClient,
    private val backendConfig: BackendConfig,
) : CurrentUserGateway {
    override suspend fun fetchCurrentUser(idToken: String): CurrentUserResponse = client
        .get("${backendConfig.baseUrl.trimEnd('/')}/api/v1/auth/me") {
            bearerAuth(idToken)
            accept(ContentType.Application.Json)
        }
        .body()
}

fun createAuthHttpClient(engine: io.ktor.client.engine.HttpClientEngineFactory<*>): HttpClient = HttpClient(engine) {
    configureAuthClient()
}

fun createAuthHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    configureAuthClient()
}

private fun HttpClientConfig<*>.configureAuthClient() {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // Deliberately no Logging plugin here: Firebase ID Tokens must never reach logs.
}

class UnavailableCurrentUserGateway : CurrentUserGateway {
    override suspend fun fetchCurrentUser(idToken: String): CurrentUserResponse =
        error("Backend synchronization is unavailable on this platform")
}
