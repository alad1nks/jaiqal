package com.alad1nks.jaiqal.feature.plants.data

import com.alad1nks.jaiqal.api.contract.CreatePlantRequest
import com.alad1nks.jaiqal.api.contract.HistoryInterval
import com.alad1nks.jaiqal.api.contract.PlantHistoryResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.network.ApiClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy

class PlantRemoteDataSourceTest {
    @Test
    fun listUsesProtectedActualBackendRoute() = runTest {
        val api = RecordingApiClient(emptyList<PlantResponse>())
        val remote = ApiPlantRemoteDataSource(api)

        remote.listPlants()

        assertEquals("/api/v1/plants", api.path)
        assertTrue(api.authenticated)
        assertEquals(HttpMethod.Get, api.builder.method)
    }

    @Test
    fun createSendsSharedContractToPlantsEndpoint() = runTest {
        val response = PlantResponse("server-id", "Aloe", null, null, "2026-07-29T00:00:00Z")
        val api = RecordingApiClient(response)
        val remote = ApiPlantRemoteDataSource(api)
        val request = CreatePlantRequest("Aloe", "Aloe vera", null)

        assertEquals(response, remote.createPlant(request))

        assertEquals(HttpMethod.Post, api.builder.method)
        assertEquals(request, api.builder.body)
    }

    @Test
    fun historyUsesServerSupportedRangeAndIntervalQuery() = runTest {
        val api = RecordingApiClient(PlantHistoryResponse("plant-a", HistoryInterval.FIVE_MINUTES, emptyList()))
        val remote = ApiPlantRemoteDataSource(api)

        remote.history(
            plantId = "plant-a",
            from = "2026-07-28T00:00:00Z",
            to = "2026-07-29T00:00:00Z",
            interval = HistoryInterval.FIVE_MINUTES,
        )

        assertEquals("/api/v1/plants/plant-a/history", api.path)
        assertEquals("2026-07-28T00:00:00Z", api.builder.url.parameters["from"])
        assertEquals("2026-07-29T00:00:00Z", api.builder.url.parameters["to"])
        assertEquals("5m", api.builder.url.parameters["interval"])
    }

    private class RecordingApiClient(private val response: Any) : ApiClient {
        var path: String = ""
        var authenticated: Boolean = false
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
