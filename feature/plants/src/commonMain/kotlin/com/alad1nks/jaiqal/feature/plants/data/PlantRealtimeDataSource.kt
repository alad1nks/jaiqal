package com.alad1nks.jaiqal.feature.plants.data

import com.alad1nks.jaiqal.api.contract.PlantTelemetryUpdate
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.serverSentEvents
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

fun interface PlantRealtimeDataSource {
    fun measurements(plantId: String): Flow<PlantTelemetryUpdate>
}

class ApiPlantRealtimeDataSource(
    private val client: HttpClient,
    private val backendConfig: BackendConfig,
    private val authProvider: AuthProvider,
    private val sessionErrorStore: SessionErrorStore,
) : PlantRealtimeDataSource {
    override fun measurements(plantId: String): Flow<PlantTelemetryUpdate> = flow {
        val token = authProvider.getIdToken(forceRefresh = false) ?: run {
            throw expiredSession()
        }
        val url = plantRealtimeUrl(backendConfig.baseUrl, plantId)

        suspend fun connect(idToken: String) {
            client.serverSentEvents(
                urlString = url,
                request = { bearerAuth(idToken) },
            ) {
                incoming.collect { event ->
                    decodePlantTelemetryEvent(event.event, event.data)?.let { emit(it) }
                }
            }
        }

        try {
            connect(token)
        } catch (failure: SSEClientException) {
            if (failure.response?.status != HttpStatusCode.Unauthorized) throw failure
            val refreshedToken = authProvider.getIdToken(forceRefresh = true) ?: throw expiredSession()
            try {
                connect(refreshedToken)
            } catch (retryFailure: SSEClientException) {
                if (retryFailure.response?.status == HttpStatusCode.Unauthorized) throw expiredSession()
                throw retryFailure
            }
        }
    }

    private fun expiredSession(): ApiException.SessionExpired {
        sessionErrorStore.reportExpiredSession()
        return ApiException.SessionExpired()
    }
}

private val realtimeJson = Json { ignoreUnknownKeys = true }

internal fun plantRealtimeUrl(baseUrl: String, plantId: String): String =
    "${baseUrl.trimEnd('/')}/api/v1/plants/$plantId/stream"

internal fun decodePlantTelemetryEvent(event: String?, data: String?): PlantTelemetryUpdate? =
    if (event == "measurement" && data != null) {
        realtimeJson.decodeFromString(PlantTelemetryUpdate.serializer(), data)
    } else {
        null
    }
