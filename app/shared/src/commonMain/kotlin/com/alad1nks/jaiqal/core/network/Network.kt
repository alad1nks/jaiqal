package com.alad1nks.jaiqal.core.network

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface AppError { data object NoInternet: AppError; data object Unauthorized: AppError; data object Timeout: AppError; data class Validation(val message:String):AppError; data class Server(val code:String?,val message:String?):AppError; data class Unknown(val cause:Throwable?):AppError }
interface AccessTokenProvider { fun accessToken(): String? }
expect fun platformHttpClient(): HttpClient
@OptIn(ExperimentalUuidApi::class)
fun createHttpClient(baseUrl:String, tokenProvider:AccessTokenProvider, debug:Boolean=false):HttpClient = platformHttpClient().config {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true; explicitNulls=false }) }
    install(HttpTimeout) { requestTimeoutMillis=20_000; connectTimeoutMillis=15_000 }
    if(debug) install(Logging) { logger=object:Logger { override fun log(message:String) { println(message.replace(Regex("(?i)Authorization:.*"), "Authorization: <redacted>")) } }; level=LogLevel.HEADERS; sanitizeHeader { it == HttpHeaders.Authorization } }
    defaultRequest { url(baseUrl); header("X-Request-ID", Uuid.random().toString()); tokenProvider.accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
}
suspend fun Throwable.toAppError():AppError = when(this) {
    is HttpRequestTimeoutException -> AppError.Timeout
    is ResponseException -> when(response.status.value) { 401 -> AppError.Unauthorized; in 400..499 -> runCatching { response.body<ApiErrorResponse>() }.fold({ AppError.Server(it.code,it.message) }, { AppError.Server(null,null) }); else -> AppError.Server(null,null) }
    else -> AppError.Unknown(null)
}
