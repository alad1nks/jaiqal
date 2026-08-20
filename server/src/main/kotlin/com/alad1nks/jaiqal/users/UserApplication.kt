package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.infrastructure.database.DeviceTokens
import com.alad1nks.jaiqal.plants.PlantRecord
import java.security.SecureRandom
import java.net.URI
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
    fun restoreDevice(userId: UUID, deviceId: UUID): DeviceRecord?
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
        val record = PlantRecord(
            UUID.randomUUID(),
            userId,
            name,
            request.species?.let(::validatedSpecies)?.takeIf(String::isNotEmpty),
            request.imageUrl?.let(::validatedImageUrl)?.takeIf(String::isNotEmpty),
            now(),
        )
        return plantResponse(store.createPlant(record))
    }
    fun updatePlant(userId: UUID, id: UUID, request: UpdatePlantRequest): PlantResponse {
        if (request.name == null && request.species == null && request.imageUrl == null) bad("EMPTY_UPDATE", "At least one field is required")
        val validated = request.copy(
            name = request.name?.let(::requiredName),
            species = request.species?.let(::validatedSpecies),
            imageUrl = request.imageUrl?.let(::validatedImageUrl),
        )
        return store.updatePlant(userId, id, validated)?.let(::plantResponse) ?: notFound()
    }
    fun archivePlant(userId: UUID, id: UUID) { if (!store.archivePlant(userId, id, now())) notFound() }

    fun claimDevice(userId: UUID, request: ClaimDeviceRequest): DeviceResponse {
        val plantId = uuid(request.plantId)
        val claimCode = request.claimCode.trim()
        if (!CLAIM_CODE_PATTERN.matches(claimCode)) {
            bad("INVALID_CLAIM_CODE", "Claim code must be 32 lowercase hexadecimal characters")
        }
        return store.claimDevice(userId, plantId, tokenHash(claimCode), now())?.let(::deviceResponse) ?: notFound()
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
    fun restoreDevice(userId: UUID, id: UUID): DeviceResponse =
        store.restoreDevice(userId, id)?.let(::deviceResponse) ?: notFound()

    private fun newToken() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun tokenHash(value: String) = DeviceTokens.hashHex(value)
    private fun now() = OffsetDateTime.now(clock)
    private fun requiredName(value: String) = value.trim().also {
        if (it.isEmpty() || it.length > MAX_NAME_LENGTH || it.hasControlCharacters()) {
            bad("INVALID_NAME", "Name must contain between 1 and 255 characters without control characters")
        }
    }
    private fun validatedSpecies(value: String) = value.trim().also {
        if (it.length > MAX_SPECIES_LENGTH || it.hasControlCharacters()) {
            bad("INVALID_SPECIES", "Species must not exceed 255 characters or contain control characters")
        }
    }
    private fun validatedImageUrl(value: String) = value.trim().also { normalized ->
        if (normalized.isEmpty()) return@also
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (
            normalized.length > MAX_IMAGE_URL_LENGTH ||
            normalized.hasControlCharacters() ||
            uri == null ||
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            bad("INVALID_IMAGE_URL", "Image URL must be an HTTPS URL without credentials and contain at most 2048 characters")
        }
    }
    private fun uuid(value: String) = runCatching { UUID.fromString(value) }.getOrElse { bad("INVALID_ID", "Identifier is invalid") }
    private fun plantResponse(v: PlantRecord) = PlantResponse(v.id.toString(), v.name, v.species, v.imageUrl, v.createdAt.toString())
    private fun deviceResponse(v: DeviceRecord) = DeviceResponse(v.id.toString(), v.plantId?.toString(), v.name, v.firmwareVersion, v.lastSeenAt?.toString(), v.soilDryRaw, v.soilWetRaw)
    private fun bad(code: String, message: String): Nothing = throw UserApiException(400, code, message)
    private fun notFound(): Nothing = throw UserApiException(404, "NOT_FOUND", "Resource was not found")

    private companion object {
        const val MAX_NAME_LENGTH = 255
        const val MAX_SPECIES_LENGTH = 255
        const val MAX_IMAGE_URL_LENGTH = 2_048
        val CLAIM_CODE_PATTERN = Regex("^[0-9a-f]{32}$")
    }
}

private fun String.hasControlCharacters(): Boolean = any(Char::isISOControl)

class UserApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)
