package com.alad1nks.jaiqal.feature.alerts.data

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.AlertStatus
import com.alad1nks.jaiqal.api.contract.AlertType
import com.alad1nks.jaiqal.api.contract.PutAlertRuleRequest
import com.alad1nks.jaiqal.api.contract.PutAlertRulesRequest
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy

class AlertRemoteDataSourceTest {
    @Test
    fun rulesUseProtectedPlantEndpointsAndSharedRequest() = runTest {
        val response = listOf(AlertRuleResponse("r1", AlertType.LOW_SOIL_MOISTURE, 25.0, 60, 30, true))
        val api = RecordingApiClient(response)
        val remote = ApiAlertRemoteDataSource(api)
        val request = PutAlertRulesRequest(listOf(PutAlertRuleRequest(AlertType.LOW_SOIL_MOISTURE, 20.0)))

        assertEquals(response, remote.putRules("plant-a", request))

        assertEquals("/api/v1/plants/plant-a/alert-rules", api.path)
        assertEquals(HttpMethod.Put, api.builder.method)
        assertEquals(request, api.builder.body)
        assertTrue(api.authenticated)
    }

    @Test
    fun acknowledgeUsesActualPostEndpoint() = runTest {
        val event = AlertEventResponse(
            "alert-a", AlertType.DEVICE_OFFLINE, AlertStatus.ACTIVE,
            "2026-08-12T00:00:00Z", acknowledgedAt = "2026-08-12T00:01:00Z",
            lastObservedAt = "2026-08-12T00:01:00Z",
        )
        val api = RecordingApiClient(event)

        assertEquals(event, ApiAlertRemoteDataSource(api).acknowledge("plant-a", "alert-a"))
        assertEquals("/api/v1/plants/plant-a/alerts/alert-a/acknowledge", api.path)
        assertEquals(HttpMethod.Post, api.builder.method)
        assertTrue(api.authenticated)
    }

    private class RecordingApiClient(private val response: Any) : ApiClient {
        var path = ""
        var authenticated = false
        var builder = HttpRequestBuilder()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> request(
            path: String,
            serializer: DeserializationStrategy<T>,
            authenticated: Boolean,
            configure: HttpRequestBuilder.() -> Unit,
        ): T {
            this.path = path
            this.authenticated = authenticated
            builder = HttpRequestBuilder().apply(configure)
            return response as T
        }
    }
}
