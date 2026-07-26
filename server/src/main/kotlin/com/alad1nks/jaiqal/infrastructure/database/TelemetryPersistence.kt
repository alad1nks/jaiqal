package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.auth.DevicePrincipal
import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.telemetry.IngestionResult
import com.alad1nks.jaiqal.telemetry.MeasurementRecord
import com.alad1nks.jaiqal.telemetry.PreparedMeasurement
import com.alad1nks.jaiqal.telemetry.TelemetryStore
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest

class ExposedDeviceTokenAuthenticator(private val database: Database) : DeviceTokenAuthenticator {
    override suspend fun authenticate(token: String): DevicePrincipal? {
        val candidate = DeviceTokens.hash(token)
        return transaction(database) {
            DevicesTable.selectAll().firstOrNull { row ->
                MessageDigest.isEqual(candidate, DeviceTokens.decodeHash(row[DevicesTable.tokenHash]))
            }?.let { DevicePrincipal(it[DevicesTable.id], it[DevicesTable.disabledAt] != null) }
        }
    }
}

object DeviceTokens {
    fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    fun hashHex(token: String): String = hash(token).joinToString("") { "%02x".format(it) }
    internal fun decodeHash(value: String): ByteArray = runCatching {
        require(value.length == 64)
        ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(32) }
}

class ExposedTelemetryStore(private val database: Database) : TelemetryStore {
    override fun ingest(device: DeviceRecord, measurements: List<PreparedMeasurement>) = transaction(database) {
        val results = measurements.map { prepared ->
            val m = prepared.measurement
            val statement = MeasurementsTable.insertIgnore {
                it[deviceId] = m.deviceId; it[sequence] = m.sequence; it[measuredAt] = m.measuredAt
                it[receivedAt] = m.receivedAt; it[soilMoistureRaw] = m.soilMoistureRaw
                it[soilMoisturePercent] = m.soilMoisturePercent; it[airTemperatureCelsius] = m.airTemperatureCelsius
                it[airHumidityPercent] = m.airHumidityPercent; it[lightRaw] = m.lightRaw
                it[extra] = Json.parseToJsonElement(m.extra)
            }
            val record = statement.resultedValues?.singleOrNull()?.let { MeasurementRecord(it[MeasurementsTable.id], m) }
            record?.let(::upsertLatestIfNewer)
            IngestionResult(record, duplicate = record == null)
        }
        val last = measurements.last()
        DevicesTable.update({ DevicesTable.id eq device.id }) {
            it[lastSeenAt] = last.measurement.receivedAt
            last.firmwareVersion?.let { version -> it[firmwareVersion] = version }
        }
        results
    }

    private fun upsertLatestIfNewer(record: MeasurementRecord) {
        val current = DeviceLatestStateTable.selectAll().where { DeviceLatestStateTable.deviceId eq record.measurement.deviceId }.singleOrNull()
        val currentMeasuredAt = current?.let { state ->
            MeasurementsTable.selectAll().where { MeasurementsTable.id eq state[DeviceLatestStateTable.measurementId] }
                .single()[MeasurementsTable.measuredAt]
        }
        if (currentMeasuredAt == null || record.measurement.measuredAt.isAfter(currentMeasuredAt)) {
            val changed = DeviceLatestStateTable.update({ DeviceLatestStateTable.deviceId eq record.measurement.deviceId }) {
                it[measurementId] = record.id; it[updatedAt] = record.measurement.receivedAt
            }
            if (changed == 0) DeviceLatestStateTable.insertIgnore {
                it[deviceId] = record.measurement.deviceId; it[measurementId] = record.id
                it[updatedAt] = record.measurement.receivedAt
            }
        }
    }
}
