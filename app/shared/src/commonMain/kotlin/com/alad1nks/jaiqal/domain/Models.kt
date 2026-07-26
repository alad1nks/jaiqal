package com.alad1nks.jaiqal.domain

import kotlinx.coroutines.flow.Flow

data class Plant(val id: String, val name: String, val species: String? = null, val imageUrl: String? = null)
data class PlantMeasurement(val deviceId: String, val measuredAt: String, val soilPercent: Double?, val temperature: Double?, val humidity: Double?, val lightRaw: Double?, val online: Boolean)
data class MeasurementPoint(val measuredAt: String, val value: Double?)
enum class MeasurementRange { DAY, WEEK, MONTH }
enum class MeasurementInterval { FIVE_MINUTES, ONE_HOUR, ONE_DAY }
fun MeasurementRange.interval() = when (this) { MeasurementRange.DAY -> MeasurementInterval.FIVE_MINUTES; MeasurementRange.WEEK -> MeasurementInterval.ONE_HOUR; MeasurementRange.MONTH -> MeasurementInterval.ONE_DAY }

data class CreatePlantCommand(val name: String, val species: String?)
interface PlantRepository {
    fun observePlants(): Flow<List<Plant>>
    fun observePlant(id: String): Flow<Plant?>
    suspend fun refreshPlants()
    suspend fun createPlant(command: CreatePlantCommand): Plant
    suspend fun updatePlant(id: String, command: CreatePlantCommand)
    suspend fun archivePlant(id: String)
    suspend fun clear()
}
interface MeasurementRepository {
    fun observeLatest(plantId: String): Flow<PlantMeasurement?>
    fun observeHistory(plantId: String, range: MeasurementRange): Flow<List<MeasurementPoint>>
    suspend fun refreshLatest(plantId: String)
    suspend fun refreshHistory(plantId: String, range: MeasurementRange)
    fun subscribeRealtime(plantId: String): AutoCloseable
}
