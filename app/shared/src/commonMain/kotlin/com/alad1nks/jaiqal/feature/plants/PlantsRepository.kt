package com.alad1nks.jaiqal.feature.plants

import com.alad1nks.jaiqal.api.contract.CreatePlantRequest
import com.alad1nks.jaiqal.api.contract.PlantResponse
import com.alad1nks.jaiqal.api.contract.UpdatePlantRequest
import kotlinx.coroutines.flow.Flow

data class Plant(
    val id: String,
    val name: String,
    val species: String?,
    val imageUrl: String?,
    val createdAt: String,
)

fun PlantResponse.toDomain() = Plant(id, name, species, imageUrl, createdAt)

interface PlantsRepository {
    /** Cache-first stream; refresh never deletes a previously valid cache on failure. */
    fun observePlants(accountId: String): Flow<List<Plant>>
    suspend fun refresh(accountId: String)
    suspend fun create(request: CreatePlantRequest): Plant
    suspend fun update(id: String, request: UpdatePlantRequest): Plant
    suspend fun clearAccount(accountId: String)
}
