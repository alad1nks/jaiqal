package com.alad1nks.jaiqal.plants

import java.time.OffsetDateTime
import java.util.UUID

data class PlantRecord(
    val id: UUID, val userId: UUID, val name: String, val species: String? = null,
    val imageUrl: String? = null, val createdAt: OffsetDateTime, val archivedAt: OffsetDateTime? = null,
)

interface PlantRepository {
    fun create(plant: PlantRecord): PlantRecord
    fun findById(id: UUID): PlantRecord?
    fun findByUserId(userId: UUID): List<PlantRecord>
}
