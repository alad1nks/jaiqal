package com.alad1nks.jaiqal.feature.devices.data

import com.alad1nks.jaiqal.api.contract.DeviceResponse
import com.alad1nks.jaiqal.api.contract.PlantLatestResponse
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.OfflineCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface DeviceLocalDataSource {
    fun observePlants(): Flow<List<PlantResponse>>
    fun observeDevices(): Flow<List<DeviceResponse>>
    fun observeLatest(): Flow<List<PlantLatestResponse>>
    suspend fun replaceDevices(devices: List<DeviceResponse>)
    suspend fun upsertDevice(device: DeviceResponse)
    suspend fun upsertLatest(latest: PlantLatestResponse)
}

class CacheDeviceLocalDataSource(
    private val cache: OfflineCache,
    private val sessionStore: UserSessionStore,
) : DeviceLocalDataSource {
    override fun observePlants(): Flow<List<PlantResponse>> = cache.observePlants(accountId())
    override fun observeDevices(): Flow<List<DeviceResponse>> = cache.observeDevices(accountId())
    override fun observeLatest(): Flow<List<PlantLatestResponse>> = cache.observeLatestStates(accountId())

    override suspend fun replaceDevices(devices: List<DeviceResponse>) {
        cache.replaceDevices(accountId(), devices)
    }

    override suspend fun upsertDevice(device: DeviceResponse) {
        val devices = observeDevices().first().filterNot { it.id == device.id } + device
        replaceDevices(devices)
    }

    override suspend fun upsertLatest(latest: PlantLatestResponse) {
        cache.replaceLatestState(accountId(), latest)
    }

    private fun accountId(): String = sessionStore.session.value?.userId
        ?: error("An internal user session is required for device data")
}
