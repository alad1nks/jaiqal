package com.alad1nks.jaiqal.feature.alerts.data

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.AlertRuleResponse
import com.alad1nks.jaiqal.api.contract.PutAlertRulesRequest
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer

interface AlertRemoteDataSource {
    suspend fun plants(): List<PlantResponse>
    suspend fun events(plantId: String): List<AlertEventResponse>
    suspend fun rules(plantId: String): List<AlertRuleResponse>
    suspend fun putRules(plantId: String, request: PutAlertRulesRequest): List<AlertRuleResponse>
    suspend fun acknowledge(plantId: String, alertId: String): AlertEventResponse
}

class ApiAlertRemoteDataSource(private val apiClient: ApiClient) : AlertRemoteDataSource {
    override suspend fun plants(): List<PlantResponse> = apiClient.request(
        path = "/api/v1/plants",
        serializer = ListSerializer(PlantResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun events(plantId: String): List<AlertEventResponse> = apiClient.request(
        path = "/api/v1/plants/$plantId/alerts",
        serializer = ListSerializer(AlertEventResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun rules(plantId: String): List<AlertRuleResponse> = apiClient.request(
        path = "/api/v1/plants/$plantId/alert-rules",
        serializer = ListSerializer(AlertRuleResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun putRules(
        plantId: String,
        request: PutAlertRulesRequest,
    ): List<AlertRuleResponse> = apiClient.request(
        path = "/api/v1/plants/$plantId/alert-rules",
        serializer = ListSerializer(AlertRuleResponse.serializer()),
        authenticated = true,
    ) {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun acknowledge(plantId: String, alertId: String): AlertEventResponse = apiClient.request(
        path = "/api/v1/plants/$plantId/alerts/$alertId/acknowledge",
        serializer = AlertEventResponse.serializer(),
        authenticated = true,
    ) { method = HttpMethod.Post }
}
