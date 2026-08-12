package com.alad1nks.jaiqal.feature.devices.data

import com.alad1nks.jaiqal.api.contract.ClaimDeviceRequest
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.UpdateCalibrationRequest
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer

interface DeviceRemoteDataSource {
    suspend fun listDevices(): List<DeviceResponse>
    suspend fun getDevice(deviceId: String): DeviceResponse
    suspend fun claimDevice(request: ClaimDeviceRequest): DeviceResponse
    suspend fun latest(plantId: String): PlantLatestResponse
    suspend fun updateCalibration(deviceId: String, request: UpdateCalibrationRequest): DeviceResponse
}

class ApiDeviceRemoteDataSource(private val apiClient: ApiClient) : DeviceRemoteDataSource {
    override suspend fun listDevices(): List<DeviceResponse> = apiClient.request(
        path = "/api/v1/devices",
        serializer = ListSerializer(DeviceResponse.serializer()),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun getDevice(deviceId: String): DeviceResponse = apiClient.request(
        path = "/api/v1/devices/$deviceId",
        serializer = DeviceResponse.serializer(),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun claimDevice(request: ClaimDeviceRequest): DeviceResponse = apiClient.request(
        path = "/api/v1/devices/claim",
        serializer = DeviceResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun latest(plantId: String): PlantLatestResponse = apiClient.request(
        path = "/api/v1/plants/$plantId/latest",
        serializer = PlantLatestResponse.serializer(),
        authenticated = true,
    ) { method = HttpMethod.Get }

    override suspend fun updateCalibration(
        deviceId: String,
        request: UpdateCalibrationRequest,
    ): DeviceResponse = apiClient.request(
        path = "/api/v1/devices/$deviceId/calibration",
        serializer = DeviceResponse.serializer(),
        authenticated = true,
    ) {
        method = HttpMethod.Patch
        contentType(ContentType.Application.Json)
        setBody(request)
    }
}
