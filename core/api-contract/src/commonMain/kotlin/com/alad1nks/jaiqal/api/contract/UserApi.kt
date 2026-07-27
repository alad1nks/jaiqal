package com.alad1nks.jaiqal.api.contract

import kotlinx.serialization.Serializable

@Serializable data class CurrentUserResponse(
    val id: String,
    val email: String? = null,
    val emailVerified: Boolean,
)

@Serializable data class CreatePlantRequest(val name: String, val species: String? = null, val imageUrl: String? = null)
@Serializable data class UpdatePlantRequest(val name: String? = null, val species: String? = null, val imageUrl: String? = null)
@Serializable data class PlantResponse(
    val id: String, val name: String, val species: String? = null, val imageUrl: String? = null,
    val createdAt: String,
)

@Serializable data class ClaimDeviceRequest(val claimCode: String, val plantId: String)
@Serializable data class UpdateDeviceRequest(val name: String? = null, val plantId: String? = null)
@Serializable data class UpdateCalibrationRequest(val soilDryRaw: Int, val soilWetRaw: Int)
@Serializable data class DeviceResponse(
    val id: String, val plantId: String?, val name: String, val firmwareVersion: String? = null,
    val lastSeenAt: String? = null, val soilDryRaw: Int? = null, val soilWetRaw: Int? = null,
)
@Serializable data class RotateDeviceTokenResponse(val device: DeviceResponse, val token: String)
