package com.alad1nks.jaiqal.feature.devices.domain

import com.alad1nks.jaiqal.api.contract.ClaimDeviceRequest
import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.UpdateCalibrationRequest
import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.feature.devices.data.DeviceLocalDataSource
import com.alad1nks.jaiqal.feature.devices.data.DeviceRemoteDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class DeviceOverview(
    val device: DeviceResponse,
    val plant: PlantResponse?,
    val latest: PlantLatestResponse?,
)

data class CalibrationSample(
    val raw: Int,
    val measuredAt: String,
)

class ClaimResultUncertain(cause: Throwable) : Exception(
    "The claim result is unknown; reconcile it before resubmitting",
    cause,
)

class DeviceOffline : Exception("The device is offline")
class MeasurementUnavailable : Exception("A soil moisture raw measurement is unavailable")

interface DeviceRepository {
    fun observePlants(): Flow<List<PlantResponse>>
    fun observeDevices(): Flow<List<DeviceOverview>>
    suspend fun refreshDevices()
    suspend fun claimDevice(claimCode: String, plantId: String): DeviceResponse
    suspend fun reconcileClaim(plantId: String): DeviceResponse?
    suspend fun captureSoilSample(deviceId: String): CalibrationSample
    suspend fun updateCalibration(deviceId: String, dryRaw: Int, wetRaw: Int): DeviceResponse
}

class OfflineFirstDeviceRepository(
    private val remote: DeviceRemoteDataSource,
    private val local: DeviceLocalDataSource,
) : DeviceRepository {
    private val uncertainClaimBaselines = mutableMapOf<String, Set<String>>()

    override fun observePlants(): Flow<List<PlantResponse>> = local.observePlants()

    override fun observeDevices(): Flow<List<DeviceOverview>> = combine(
        local.observeDevices(),
        local.observePlants(),
        local.observeLatest(),
    ) { devices, plants, latest ->
        devices.map { device ->
            DeviceOverview(
                device = device,
                plant = plants.firstOrNull { it.id == device.plantId },
                latest = latest.firstOrNull { it.deviceId == device.id },
            )
        }
    }

    override suspend fun refreshDevices() {
        val devices = remote.listDevices()
        local.replaceDevices(devices)
        devices.mapNotNull(DeviceResponse::plantId).distinct().forEach { plantId ->
            try {
                local.upsertLatest(remote.latest(plantId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Device metadata remains useful when telemetry has not arrived or is temporarily unavailable.
            }
        }
    }

    override suspend fun claimDevice(claimCode: String, plantId: String): DeviceResponse {
        val devicesBeforeClaim = remote.listDevices()
        replaceDeviceCacheBestEffort(devicesBeforeClaim)
        val baseline = devicesBeforeClaim
            .filter { it.plantId == plantId }
            .mapTo(mutableSetOf(), DeviceResponse::id)
        val device = try {
            remote.claimDevice(ClaimDeviceRequest(claimCode, plantId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ApiException.Connectivity) {
            uncertainClaimBaselines[plantId] = baseline
            throw ClaimResultUncertain(failure)
        } catch (failure: ApiException.Timeout) {
            uncertainClaimBaselines[plantId] = baseline
            throw ClaimResultUncertain(failure)
        }
        uncertainClaimBaselines.remove(plantId)
        updateCacheWithoutChangingResult(device)
        return device
    }

    override suspend fun reconcileClaim(plantId: String): DeviceResponse? {
        val baseline = uncertainClaimBaselines[plantId] ?: return null
        val devices = remote.listDevices()
        replaceDeviceCacheBestEffort(devices)
        return devices.firstOrNull { it.plantId == plantId && it.id !in baseline }
            ?.also { uncertainClaimBaselines.remove(plantId) }
    }

    override suspend fun captureSoilSample(deviceId: String): CalibrationSample {
        val device = remote.getDevice(deviceId)
        val plantId = device.plantId ?: throw DeviceOffline()
        val latest = remote.latest(plantId)
        if (latest.deviceId != deviceId || !latest.online) throw DeviceOffline()
        val raw = latest.soilMoistureRaw ?: throw MeasurementUnavailable()
        updateCacheWithoutChangingResult(device)
        try {
            local.upsertLatest(latest)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The captured server value is still authoritative.
        }
        return CalibrationSample(raw, latest.measuredAt)
    }

    override suspend fun updateCalibration(deviceId: String, dryRaw: Int, wetRaw: Int): DeviceResponse {
        require(dryRaw >= 0 && wetRaw >= 0 && dryRaw != wetRaw)
        val device = remote.updateCalibration(deviceId, UpdateCalibrationRequest(dryRaw, wetRaw))
        updateCacheWithoutChangingResult(device)
        return device
    }

    private suspend fun updateCacheWithoutChangingResult(device: DeviceResponse) {
        try {
            local.upsertDevice(device)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The server is authoritative; the next refresh repairs a failed cache write.
        }
    }

    private suspend fun replaceDeviceCacheBestEffort(devices: List<DeviceResponse>) {
        try {
            local.replaceDevices(devices)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Reconciliation is based on the authoritative server list, not cache write success.
        }
    }
}
