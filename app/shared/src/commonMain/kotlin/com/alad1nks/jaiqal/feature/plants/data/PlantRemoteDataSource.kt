package com.alad1nks.jaiqal.feature.plants.data

import com.alad1nks.jaiqal.api.contract.AlertEventResponse
import com.alad1nks.jaiqal.api.contract.CreatePlantRequest
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.UpdatePlantRequest
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer

interface PlantRemoteDataSource {
    suspend fun listPlants(): List<PlantResponse>
    suspend fun getPlant(plantId: String): PlantResponse
    suspend fun createPlant(request: CreatePlantRequest): PlantResponse
    suspend fun updatePlant(plantId: String, request: UpdatePlantRequest): PlantResponse
    suspend fun listDevices(): List<DeviceResponse>
    suspend fun latest(plantId: String): PlantLatestResponse
    suspend fun history(
        plantId: String,
        from: String,
        to: String,
        interval: HistoryInterval,
    ): PlantHistoryResponse
    suspend fun alerts(plantId: String): List<AlertEventResponse>
}

class ApiPlantRemoteDataSource(private val apiClient: ApiClient) : PlantRemoteDataSource {
    override suspend fun listPlants(): List<PlantResponse> = apiClient.request(
        path = "/api/v1/plants",
        serializer = ListSerializer(PlantResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun getPlant(plantId: String): PlantResponse = apiClient.request(
        path = "/api/v1/plants/$plantId",
        serializer = PlantResponse.serializer(),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun createPlant(request: CreatePlantRequest): PlantResponse = apiClient.request(
        path = "/api/v1/plants",
        serializer = PlantResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun updatePlant(plantId: String, request: UpdatePlantRequest): PlantResponse = apiClient.request(
        path = "/api/v1/plants/$plantId",
        serializer = PlantResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Patch
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun listDevices(): List<DeviceResponse> = apiClient.request(
        path = "/api/v1/devices",
        serializer = ListSerializer(DeviceResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun latest(plantId: String): PlantLatestResponse = apiClient.request(
        path = "/api/v1/plants/$plantId/latest",
        serializer = PlantLatestResponse.serializer(),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun history(
        plantId: String,
        from: String,
        to: String,
        interval: HistoryInterval,
    ): PlantHistoryResponse = apiClient.request(
        path = "/api/v1/plants/$plantId/history",
        serializer = PlantHistoryResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Get
        url {
            parameters.append("from", from)
            parameters.append("to", to)
            parameters.append("interval", interval.queryValue)
        }
    }

    override suspend fun alerts(plantId: String): List<AlertEventResponse> = apiClient.request(
        path = "/api/v1/plants/$plantId/alerts",
        serializer = ListSerializer(AlertEventResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    private val HistoryInterval.queryValue: String
        get() = when (this) {
            HistoryInterval.RAW -> "raw"
            HistoryInterval.FIVE_MINUTES -> "5m"
            HistoryInterval.ONE_HOUR -> "1h"
            HistoryInterval.ONE_DAY -> "1d"
        }
}
