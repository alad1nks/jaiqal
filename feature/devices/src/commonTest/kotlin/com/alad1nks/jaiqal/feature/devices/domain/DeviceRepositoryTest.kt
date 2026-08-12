package com.alad1nks.jaiqal.feature.devices.domain

import com.alad1nks.jaiqal.api.contract.ClaimDeviceRequest
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.UpdateCalibrationRequest
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.devices.data.DeviceLocalDataSource
import com.alad1nks.jaiqal.feature.devices.data.DeviceRemoteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class DeviceRepositoryTest {
    @Test
    fun uncertainClaimIsReconciledFromAuthoritativeDeviceListBeforeRetry() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote().apply {
            devices = listOf(device(plantId = "plant-a").copy(id = "existing-device"))
            claimFailure = ApiException.Connectivity(Exception("response lost"))
        }
        val repository = OfflineFirstDeviceRepository(remote, local)

        assertFailsWith<ClaimResultUncertain> { repository.claimDevice("secret-code", "plant-a") }

        remote.devices = remote.devices + device(plantId = "plant-a")
        val reconciled = repository.reconcileClaim("plant-a")

        assertEquals("device-a", reconciled?.id)
        assertEquals(remote.devices, local.devices.value)
    }

    @Test
    fun reversedAdcDirectionIsSentUnchanged() = runTest {
        val remote = FakeRemote()
        val repository = OfflineFirstDeviceRepository(remote, FakeLocal())

        repository.updateCalibration("device-a", dryRaw = 210, wetRaw = 820)

        assertEquals(UpdateCalibrationRequest(210, 820), remote.calibration)
    }

    @Test
    fun reconciliationDoesNotMistakePreviouslyAttachedDeviceForClaimSuccess() = runTest {
        val existing = device(plantId = "plant-a").copy(id = "existing-device")
        val remote = FakeRemote().apply {
            devices = listOf(existing)
            claimFailure = ApiException.Timeout(Exception("response lost"))
        }
        val repository = OfflineFirstDeviceRepository(remote, FakeLocal())

        assertFailsWith<ClaimResultUncertain> { repository.claimDevice("secret-code", "plant-a") }

        assertEquals(null, repository.reconcileClaim("plant-a"))
    }

    @Test
    fun equalCalibrationValuesAreRejectedBeforeNetwork() = runTest {
        val remote = FakeRemote()
        val repository = OfflineFirstDeviceRepository(remote, FakeLocal())

        assertFailsWith<IllegalArgumentException> {
            repository.updateCalibration("device-a", dryRaw = 500, wetRaw = 500)
        }
        assertEquals(null, remote.calibration)
    }

    @Test
    fun offlineLatestMeasurementCannotBeCaptured() = runTest {
        val remote = FakeRemote().apply { latestResponse = latest(online = false) }
        val repository = OfflineFirstDeviceRepository(remote, FakeLocal())

        assertFailsWith<DeviceOffline> { repository.captureSoilSample("device-a") }
    }

    private class FakeRemote : DeviceRemoteDataSource {
        var devices = emptyList<DeviceResponse>()
        var claimFailure: Throwable? = null
        var calibration: UpdateCalibrationRequest? = null
        var latestResponse = latest(online = true)

        override suspend fun listDevices() = devices
        override suspend fun getDevice(deviceId: String) = device()
        override suspend fun claimDevice(request: ClaimDeviceRequest): DeviceResponse {
            claimFailure?.let { throw it }
            return device(request.plantId)
        }
        override suspend fun latest(plantId: String) = latestResponse
        override suspend fun updateCalibration(deviceId: String, request: UpdateCalibrationRequest): DeviceResponse {
            calibration = request
            return device().copy(soilDryRaw = request.soilDryRaw, soilWetRaw = request.soilWetRaw)
        }
    }

    private class FakeLocal : DeviceLocalDataSource {
        val plants = MutableStateFlow(listOf(PlantResponse("plant-a", "Aloe", createdAt = "now")))
        val devices = MutableStateFlow(emptyList<DeviceResponse>())
        val latest = MutableStateFlow(emptyList<PlantLatestResponse>())
        override fun observePlants(): Flow<List<PlantResponse>> = plants
        override fun observeDevices(): Flow<List<DeviceResponse>> = devices
        override fun observeLatest(): Flow<List<PlantLatestResponse>> = latest
        override suspend fun replaceDevices(devices: List<DeviceResponse>) { this.devices.value = devices }
        override suspend fun upsertDevice(device: DeviceResponse) {
            devices.value = devices.value.filterNot { it.id == device.id } + device
        }
        override suspend fun upsertLatest(latest: PlantLatestResponse) {
            this.latest.value = this.latest.value.filterNot { it.deviceId == latest.deviceId } + latest
        }
    }

    private companion object {
        fun device(plantId: String = "plant-a") = DeviceResponse("device-a", plantId, "Sensor")
        fun latest(online: Boolean) = PlantLatestResponse(
            plantId = "plant-a",
            deviceId = "device-a",
            measuredAt = "2026-08-12T00:00:00Z",
            receivedAt = "2026-08-12T00:00:01Z",
            soilMoisturePercent = null,
            soilMoistureRaw = 640,
            airTemperatureCelsius = null,
            airHumidityPercent = null,
            lightRaw = null,
            online = online,
            calibrated = false,
        )
    }
}
