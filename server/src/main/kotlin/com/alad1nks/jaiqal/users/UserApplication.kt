package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.infrastructure.database.DeviceTokens
import com.alad1nks.jaiqal.plants.PlantRecord
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

interface UserApplicationStore {
    fun listPlants(userId: UUID): List<PlantRecord>
    fun findPlant(userId: UUID, plantId: UUID): PlantRecord?
    fun createPlant(plant: PlantRecord): PlantRecord
    fun updatePlant(userId: UUID, plantId: UUID, request: UpdatePlantRequest): PlantRecord?
    fun archivePlant(userId: UUID, plantId: UUID, at: OffsetDateTime): Boolean
    fun claimDevice(userId: UUID, plantId: UUID, claimHash: String, now: OffsetDateTime): DeviceRecord?
    fun listDevices(userId: UUID): List<DeviceRecord>
    fun findDevice(userId: UUID, deviceId: UUID): DeviceRecord?
    fun updateDevice(userId: UUID, deviceId: UUID, name: String?, plantId: UUID?): DeviceRecord?
    fun updateCalibration(userId: UUID, deviceId: UUID, dry: Int, wet: Int): DeviceRecord?
    fun rotateDeviceToken(userId: UUID, deviceId: UUID, tokenHash: String): DeviceRecord?
}

class UserApplicationService(
    private val store: UserApplicationStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun listPlants(userId: UUID) = store.listPlants(userId).map(::plantResponse)
    fun getPlant(userId: UUID, id: UUID) = store.findPlant(userId, id)?.let(::plantResponse) ?: notFound()
    fun createPlant(userId: UUID, request: CreatePlantRequest): PlantResponse {
        val name = requiredName(request.name)
        val record = PlantRecord(UUID.randomUUID(), userId, name, clean(request.species), clean(request.imageUrl), now())
        return plantResponse(store.createPlant(record))
    }
    fun updatePlant(userId: UUID, id: UUID, request: UpdatePlantRequest): PlantResponse {
        if (request.name == null && request.species == null && request.imageUrl == null) bad("EMPTY_UPDATE", "At least one field is required")
        request.name?.let(::requiredName)
        return store.updatePlant(userId, id, request)?.let(::plantResponse) ?: notFound()
    }
    fun archivePlant(userId: UUID, id: UUID) { if (!store.archivePlant(userId, id, now())) notFound() }

    fun claimDevice(userId: UUID, request: ClaimDeviceRequest): DeviceResponse {
        val plantId = uuid(request.plantId)
        if (request.claimCode.isBlank()) bad("INVALID_CLAIM_CODE", "Claim code is required")
        return store.claimDevice(userId, plantId, tokenHash(request.claimCode), now())?.let(::deviceResponse) ?: notFound()
    }
    fun listDevices(userId: UUID) = store.listDevices(userId).map(::deviceResponse)
    fun getDevice(userId: UUID, id: UUID) = store.findDevice(userId, id)?.let(::deviceResponse) ?: notFound()
    fun updateDevice(userId: UUID, id: UUID, request: UpdateDeviceRequest): DeviceResponse {
        if (request.name == null && request.plantId == null) bad("EMPTY_UPDATE", "At least one field is required")
        request.name?.let(::requiredName)
        val plant = request.plantId?.let(::uuid)
        return store.updateDevice(userId, id, request.name?.trim(), plant)?.let(::deviceResponse) ?: notFound()
    }
    fun updateCalibration(userId: UUID, id: UUID, request: UpdateCalibrationRequest): DeviceResponse {
        if (request.soilDryRaw < 0 || request.soilWetRaw < 0 || request.soilDryRaw == request.soilWetRaw) bad("INVALID_CALIBRATION", "Dry and wet values must be distinct non-negative values")
        return store.updateCalibration(userId, id, request.soilDryRaw, request.soilWetRaw)?.let(::deviceResponse) ?: notFound()
    }
    fun rotateDeviceToken(userId: UUID, id: UUID): RotateDeviceTokenResponse {
        val raw = newToken()
        val device = store.rotateDeviceToken(userId, id, DeviceTokens.hashHex(raw)) ?: notFound()
        return RotateDeviceTokenResponse(deviceResponse(device), raw)
    }

    private fun newToken() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun tokenHash(value: String) = DeviceTokens.hashHex(value)
    private fun now() = OffsetDateTime.now(clock)
    private fun requiredName(value: String) = value.trim().also { if (it.isEmpty() || it.length > 255) bad("INVALID_NAME", "Name must contain between 1 and 255 characters") }
    private fun clean(value: String?) = value?.trim()?.takeIf(String::isNotEmpty)
    private fun uuid(value: String) = runCatching { UUID.fromString(value) }.getOrElse { bad("INVALID_ID", "Identifier is invalid") }
    private fun plantResponse(v: PlantRecord) = PlantResponse(v.id.toString(), v.name, v.species, v.imageUrl, v.createdAt.toString())
    private fun deviceResponse(v: DeviceRecord) = DeviceResponse(v.id.toString(), v.plantId?.toString(), v.name, v.firmwareVersion, v.lastSeenAt?.toString(), v.soilDryRaw, v.soilWetRaw)
    private fun bad(code: String, message: String): Nothing = throw UserApiException(400, code, message)
    private fun notFound(): Nothing = throw UserApiException(404, "NOT_FOUND", "Resource was not found")
}

class UserApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)
