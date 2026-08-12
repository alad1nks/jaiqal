package com.alad1nks.jaiqal.feature.devices.data

import com.alad1nks.jaiqal.api.contract.ClaimDeviceRequest
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.UpdateCalibrationRequest
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy

class DeviceRemoteDataSourceTest {
    @Test
    fun claimUsesProtectedUserEndpointAndSharedContract() = runTest {
        val response = device()
        val api = RecordingApiClient(response)
        val remote = ApiDeviceRemoteDataSource(api)
        val request = ClaimDeviceRequest("one-time-code", "plant-a")

        assertEquals(response, remote.claimDevice(request))

        assertEquals("/api/v1/devices/claim", api.path)
        assertEquals(HttpMethod.Post, api.builder.method)
        assertEquals(request, api.builder.body)
        assertTrue(api.authenticated)
    }

    @Test
    fun calibrationUsesPatchWithoutAnyDeviceToken() = runTest {
        val api = RecordingApiClient(device())
        val remote = ApiDeviceRemoteDataSource(api)
        val request = UpdateCalibrationRequest(820, 210)

        remote.updateCalibration("device-a", request)

        assertEquals("/api/v1/devices/device-a/calibration", api.path)
        assertEquals(HttpMethod.Patch, api.builder.method)
        assertEquals(request, api.builder.body)
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

    private fun device() = DeviceResponse("device-a", "plant-a", "Sensor")
}
