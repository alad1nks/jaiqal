package com.alad1nks.jaiqal.devices

import java.time.OffsetDateTime
import java.util.UUID

data class DeviceRecord(
    val id: UUID, val plantId: UUID?, val name: String, val tokenHash: String,
    val firmwareVersion: String? = null, val lastSeenAt: OffsetDateTime? = null,
    val soilDryRaw: Int? = null, val soilWetRaw: Int? = null,
    val disabledAt: OffsetDateTime? = null, val createdAt: OffsetDateTime,
)

interface DeviceRepository {
    fun create(device: DeviceRecord): DeviceRecord
    fun findById(id: UUID): DeviceRecord?
    fun findByPlantId(plantId: UUID): List<DeviceRecord>
}
