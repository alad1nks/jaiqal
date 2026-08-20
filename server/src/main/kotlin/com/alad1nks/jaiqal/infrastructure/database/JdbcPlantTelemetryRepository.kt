package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.telemetry.HistoryRequest
import com.alad1nks.jaiqal.telemetry.PlantTelemetryRepository
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcPlantTelemetryRepository(private val dataSource: DataSource) : PlantTelemetryRepository {
    override fun ownsPlant(userId: UUID, plantId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM plants WHERE id = ? AND user_id = ? AND archived_at IS NULL").use {
            it.setObject(1, plantId); it.setObject(2, userId); it.executeQuery().use(ResultSet::next)
        }
    }

    override fun latest(userId: UUID, plantId: UUID): PlantLatestResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT d.id device_id, m.id measurement_id, m.measured_at, m.received_at,
                m.soil_moisture_percent, m.soil_moisture_raw, m.air_temperature_celsius,
                m.air_humidity_percent, m.light_raw, d.soil_dry_raw, d.soil_wet_raw
                FROM plants p JOIN devices d ON d.plant_id = p.id
                JOIN device_latest_state s ON s.device_id = d.id
                JOIN measurements m ON m.device_id = s.device_id AND m.id = s.measurement_id
                WHERE p.id = ? AND p.user_id = ? AND p.archived_at IS NULL AND d.disabled_at IS NULL
                ORDER BY m.measured_at DESC LIMIT 1""",
        ).use {
            it.setObject(1, plantId); it.setObject(2, userId)
            it.executeQuery().use { rows -> if (rows.next()) rows.latest(plantId) else null }
        }
    }

    override fun history(userId: UUID, plantId: UUID, request: HistoryRequest, limit: Int): List<PlantHistoryPoint>? {
        if (!ownsPlant(userId, plantId)) return null
        val bucket = when (request.interval) {
            HistoryInterval.RAW -> null
            HistoryInterval.FIVE_MINUTES -> "5 minutes"
            HistoryInterval.ONE_HOUR -> "1 hour"
            HistoryInterval.ONE_DAY -> "1 day"
        }
        val select = if (bucket == null) {
            "m.measured_at bucket, m.soil_moisture_percent, m.soil_moisture_raw::double precision soil_raw, m.air_temperature_celsius, m.air_humidity_percent, m.light_raw::double precision light"
        } else {
            "date_bin(INTERVAL '$bucket', m.measured_at, TIMESTAMPTZ '1970-01-01 00:00:00+00') bucket, AVG(m.soil_moisture_percent) soil_moisture_percent, AVG(m.soil_moisture_raw) soil_raw, AVG(m.air_temperature_celsius) air_temperature_celsius, AVG(m.air_humidity_percent) air_humidity_percent, AVG(m.light_raw) light"
        }
        val group = if (bucket == null) "" else " GROUP BY bucket"
        val sql = """SELECT $select FROM measurements m JOIN devices d ON d.id = m.device_id
            WHERE d.plant_id = ? AND m.measured_at >= ? AND m.measured_at < ?$group ORDER BY bucket ASC LIMIT ?"""
        return dataSource.connection.use { connection -> connection.prepareStatement(sql).use {
            it.setObject(1, plantId); it.setObject(2, request.from); it.setObject(3, request.to); it.setInt(4, limit)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.historyPoint()) } }
        } }
    }

    private fun ResultSet.latest(plantId: UUID) = PlantLatestResponse(
        plantId.toString(), getObject("device_id", UUID::class.java).toString(),
        getObject("measured_at").toString(), getObject("received_at").toString(),
        nullableDouble("soil_moisture_percent"), getObject("soil_moisture_raw") as Int?,
        nullableDouble("air_temperature_celsius"), nullableDouble("air_humidity_percent"), getObject("light_raw") as Int?,
        online = false, calibrated = getObject("soil_dry_raw") != null && getObject("soil_wet_raw") != null,
    )

    private fun ResultSet.historyPoint() = PlantHistoryPoint(
        getObject("bucket").toString(), nullableDouble("soil_moisture_percent"), nullableDouble("soil_raw"),
        nullableDouble("air_temperature_celsius"), nullableDouble("air_humidity_percent"), nullableDouble("light"),
    )
    private fun ResultSet.nullableDouble(column: String): Double? = getDouble(column).let { if (wasNull()) null else it }
}
