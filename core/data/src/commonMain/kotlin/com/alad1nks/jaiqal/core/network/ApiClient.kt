package com.alad1nks.jaiqal.core.network

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val REQUEST_TIMEOUT_MILLIS = 30_000L
private const val SOCKET_TIMEOUT_MILLIS = 30_000L

private val networkJson = Json { ignoreUnknownKeys = true }

interface ApiClient {
    suspend fun <T> request(
        path: String,
        serializer: DeserializationStrategy<T>,
        authenticated: Boolean = false,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): T
}

class KtorApiClient(
    private val client: HttpClient,
    private val backendConfig: BackendConfig,
    private val authenticatedRequestExecutor: AuthenticatedRequestExecutor,
) : ApiClient {
    override suspend fun <T> request(
        path: String,
        serializer: DeserializationStrategy<T>,
        authenticated: Boolean,
        configure: HttpRequestBuilder.() -> Unit,
    ): T = try {
        val send: suspend (String?) -> HttpResponse = { token ->
            client.request("${backendConfig.baseUrl.trimEnd('/')}/${path.trimStart('/')}") {
                accept(ContentType.Application.Json)
                configure()
                token?.let(::bearerAuth)
            }
        }
        val response = if (authenticated) {
            authenticatedRequestExecutor.execute { send(it) }
        } else {
            send(null)
        }
        if (response.status.value !in 200..299) throw mapBackendError(response)
        networkJson.decodeFromString(serializer, response.bodyAsText())
    } catch (failure: Throwable) {
        throw mapFailure(failure)
    }

    private suspend fun mapBackendError(response: HttpResponse): ApiException.Backend {
        val error = try {
            networkJson.decodeFromString(ApiErrorResponse.serializer(), response.bodyAsText())
        } catch (_: SerializationException) {
            null
        }
        return ApiException.Backend(
            statusCode = response.status.value,
            errorCode = error?.code ?: "HTTP_${response.status.value}",
            requestId = error?.requestId,
        )
    }

    private fun mapFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is ApiException -> failure
        is HttpRequestTimeoutException -> ApiException.Timeout(failure)
        is SerializationException -> ApiException.InvalidResponse(failure)
        is IOException -> ApiException.Connectivity(failure)
        else -> failure
    }
}

fun createApiHttpClient(
    engine: io.ktor.client.engine.HttpClientEngineFactory<*>,
    enableDebugLogging: Boolean = false,
    networkLogger: NetworkLogger = NoOpNetworkLogger,
): HttpClient = HttpClient(engine) {
    configureApiClient(enableDebugLogging, networkLogger)
}

fun createApiHttpClient(
    engine: HttpClientEngine,
    enableDebugLogging: Boolean = false,
    networkLogger: NetworkLogger = NoOpNetworkLogger,
): HttpClient = HttpClient(engine) {
    configureApiClient(enableDebugLogging, networkLogger)
}

private fun HttpClientConfig<*>.configureApiClient(
    enableDebugLogging: Boolean,
    networkLogger: NetworkLogger,
) {
    expectSuccess = false
    install(ContentNegotiation) { json(networkJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
    install(SSE) {
        // Reconnect is coordinated by the repository so it can stop on logout/background.
        maxReconnectionAttempts = 0
    }
    if (enableDebugLogging) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) = networkLogger.log(message)
            }
            level = LogLevel.INFO
            sanitizeHeader { header ->
                header == HttpHeaders.Authorization ||
                    header == HttpHeaders.Cookie ||
                    header == HttpHeaders.SetCookie
            }
        }
    }
}
