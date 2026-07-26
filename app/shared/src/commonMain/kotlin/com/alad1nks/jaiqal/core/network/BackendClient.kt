package com.alad1nks.jaiqal.core.network

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.core.auth.AuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

enum class BackendEnvironment { LOCAL_ANDROID_EMULATOR, LOCAL_IOS_SIMULATOR, PRODUCTION }

data class BackendConfig(val baseUrl: String) {
    init { require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) }

    companion object {
        fun forEnvironment(environment: BackendEnvironment, productionUrl: String? = null) = BackendConfig(
            when (environment) {
                BackendEnvironment.LOCAL_ANDROID_EMULATOR -> "http://10.0.2.2:8080"
                BackendEnvironment.LOCAL_IOS_SIMULATOR -> "http://127.0.0.1:8080"
                BackendEnvironment.PRODUCTION -> requireNotNull(productionUrl) { "Production URL must be supplied by build configuration" }
            }
        )
    }
}

class BackendException(val status: Int, val code: String, override val message: String) : Exception(message)
class SessionExpiredException : Exception("The session has expired")

fun createHttpClient() = HttpClient {
    expectSuccess = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
}

/** Adds an ephemeral Firebase ID token and retries exactly once after a 401. */
class AuthenticatedRequestExecutor(
    @PublishedApi internal val client: HttpClient,
    @PublishedApi internal val auth: AuthProvider,
    @PublishedApi internal val config: BackendConfig,
) {
    @PublishedApi internal val refreshMutex = Mutex()

    suspend inline fun <reified T> execute(noinline block: HttpRequestBuilder.() -> Unit): T {
        var token = auth.getIdToken() ?: throw SessionExpiredException()
        var response = client.request(config.baseUrl, request(token, block))
        if (response.status == HttpStatusCode.Unauthorized) {
            token = refreshMutex.withLock { auth.getIdToken(forceRefresh = true) } ?: throw SessionExpiredException()
            response = client.request(config.baseUrl, request(token, block))
        }
        if (response.status.value !in 200..299) {
            val error = runCatching { response.body<ApiErrorResponse>() }.getOrNull()
            throw BackendException(response.status.value, error?.code ?: "HTTP_ERROR", error?.message ?: "Request failed")
        }
        return response.body()
    }

    @PublishedApi
    internal fun request(token: String, block: HttpRequestBuilder.() -> Unit): HttpRequestBuilder.() -> Unit = {
        header(HttpHeaders.Authorization, "Bearer $token")
        block()
    }
}
