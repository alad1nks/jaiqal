package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.alerts.AlertRuleRecord
import com.alad1nks.jaiqal.alerts.AlertRuleRepository
import com.alad1nks.jaiqal.auth.RefreshTokenRecord
import com.alad1nks.jaiqal.auth.RefreshTokenRepository
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.notifications.NotificationOutboxRecord
import com.alad1nks.jaiqal.notifications.NotificationOutboxRepository
import com.alad1nks.jaiqal.plants.PlantRecord
import com.alad1nks.jaiqal.plants.PlantRepository
import com.alad1nks.jaiqal.telemetry.LatestDeviceState
import com.alad1nks.jaiqal.telemetry.MeasurementRecord
import com.alad1nks.jaiqal.telemetry.MeasurementRepository
import com.alad1nks.jaiqal.telemetry.NewMeasurement
import com.alad1nks.jaiqal.users.UserRecord
import com.alad1nks.jaiqal.users.UserRepository
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class ExposedUserRepository(private val database: Database) : UserRepository {
    override fun create(user: UserRecord) = transaction(database) { UsersTable.insert { it.from(user) }; user }
    override fun findById(id: UUID) = transaction(database) { UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUser() }
    override fun findByEmail(email: String) = transaction(database) { UsersTable.selectAll().where { UsersTable.email eq email }.singleOrNull()?.toUser() }
}

class ExposedPlantRepository(private val database: Database) : PlantRepository {
    override fun create(plant: PlantRecord) = transaction(database) { PlantsTable.insert { it.from(plant) }; plant }
    override fun findById(id: UUID) = transaction(database) { PlantsTable.selectAll().where { PlantsTable.id eq id }.singleOrNull()?.toPlant() }
    override fun findByUserId(userId: UUID) = transaction(database) { PlantsTable.selectAll().where { PlantsTable.userId eq userId }.map(ResultRow::toPlant) }
}

class ExposedDeviceRepository(private val database: Database) : DeviceRepository {
    override fun create(device: DeviceRecord) = transaction(database) { DevicesTable.insert { it.from(device) }; device }
    override fun findById(id: UUID) = transaction(database) { DevicesTable.selectAll().where { DevicesTable.id eq id }.singleOrNull()?.toDevice() }
    override fun findByPlantId(plantId: UUID) = transaction(database) { DevicesTable.selectAll().where { DevicesTable.plantId eq plantId }.map(ResultRow::toDevice) }
}

class ExposedMeasurementRepository(private val database: Database) : MeasurementRepository {
    override fun insert(measurement: NewMeasurement): MeasurementRecord? = transaction(database) {
        val statement = MeasurementsTable.insertIgnore {
            it[MeasurementsTable.deviceId] = measurement.deviceId; it[MeasurementsTable.sequence] = measurement.sequence
            it[MeasurementsTable.measuredAt] = measurement.measuredAt; it[MeasurementsTable.receivedAt] = measurement.receivedAt
            it[MeasurementsTable.soilMoistureRaw] = measurement.soilMoistureRaw; it[MeasurementsTable.soilMoisturePercent] = measurement.soilMoisturePercent
            it[MeasurementsTable.airTemperatureCelsius] = measurement.airTemperatureCelsius; it[MeasurementsTable.airHumidityPercent] = measurement.airHumidityPercent
            it[MeasurementsTable.lightRaw] = measurement.lightRaw; it[MeasurementsTable.extra] = Json.parseToJsonElement(measurement.extra)
        }
        statement.resultedValues?.singleOrNull()?.let { MeasurementRecord(it[MeasurementsTable.id], measurement) }
    }

    override fun findByDeviceAndSequence(deviceId: UUID, sequence: Long) = transaction(database) {
        MeasurementsTable.selectAll().where { MeasurementsTable.deviceId eq deviceId }
            .andWhere { MeasurementsTable.sequence eq sequence }.singleOrNull()?.toMeasurement()
    }

    override fun upsertLatest(state: LatestDeviceState) = transaction(database) {
        val changed = DeviceLatestStateTable.update({ DeviceLatestStateTable.deviceId eq state.deviceId }) {
            it[DeviceLatestStateTable.measurementId] = state.measurementId; it[DeviceLatestStateTable.updatedAt] = state.updatedAt
        }
        if (changed == 0) DeviceLatestStateTable.insertIgnore {
            it[DeviceLatestStateTable.deviceId] = state.deviceId
            it[DeviceLatestStateTable.measurementId] = state.measurementId
            it[DeviceLatestStateTable.updatedAt] = state.updatedAt
        }
        Unit
    }

    override fun findLatest(deviceId: UUID) = transaction(database) {
        DeviceLatestStateTable.selectAll().where { DeviceLatestStateTable.deviceId eq deviceId }.singleOrNull()?.let {
            LatestDeviceState(it[DeviceLatestStateTable.deviceId], it[DeviceLatestStateTable.measurementId], it[DeviceLatestStateTable.updatedAt])
        }
    }
}

class ExposedRefreshTokenRepository(private val database: Database) : RefreshTokenRepository {
    override fun create(token: RefreshTokenRecord) = transaction(database) { RefreshTokensTable.insert { it.from(token) }; token }
    override fun findByHash(tokenHash: String) = transaction(database) { RefreshTokensTable.selectAll().where { RefreshTokensTable.tokenHash eq tokenHash }.singleOrNull()?.toRefreshToken() }
}

class ExposedAlertRuleRepository(private val database: Database) : AlertRuleRepository {
    override fun create(rule: AlertRuleRecord) = transaction(database) { AlertRulesTable.insert { it.from(rule) }; rule }
    override fun findByPlantId(plantId: UUID) = transaction(database) { AlertRulesTable.selectAll().where { AlertRulesTable.plantId eq plantId }.map(ResultRow::toAlertRule) }
}

class ExposedNotificationOutboxRepository(private val database: Database) : NotificationOutboxRepository {
    override fun enqueue(notification: NotificationOutboxRecord) = transaction(database) {
        val id = NotificationOutboxTable.insert {
            it[NotificationOutboxTable.alertEventId] = notification.alertEventId; it[NotificationOutboxTable.channel] = notification.channel
            it[NotificationOutboxTable.payload] = Json.parseToJsonElement(notification.payload); it[NotificationOutboxTable.status] = notification.status
            it[NotificationOutboxTable.attempts] = notification.attempts; it[NotificationOutboxTable.availableAt] = notification.availableAt; it[NotificationOutboxTable.createdAt] = notification.createdAt
        }[NotificationOutboxTable.id]
        notification.copy(id = id)
    }
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.from(v: UserRecord) { this[UsersTable.id]=v.id; this[UsersTable.email]=v.email; this[UsersTable.passwordHash]=v.passwordHash; this[UsersTable.createdAt]=v.createdAt }
private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.from(v: PlantRecord) { this[PlantsTable.id]=v.id; this[PlantsTable.userId]=v.userId; this[PlantsTable.name]=v.name; this[PlantsTable.species]=v.species; this[PlantsTable.imageUrl]=v.imageUrl; this[PlantsTable.createdAt]=v.createdAt; this[PlantsTable.archivedAt]=v.archivedAt }
private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.from(v: DeviceRecord) { this[DevicesTable.id]=v.id; this[DevicesTable.plantId]=v.plantId; this[DevicesTable.name]=v.name; this[DevicesTable.tokenHash]=v.tokenHash; this[DevicesTable.firmwareVersion]=v.firmwareVersion; this[DevicesTable.lastSeenAt]=v.lastSeenAt; this[DevicesTable.soilDryRaw]=v.soilDryRaw; this[DevicesTable.soilWetRaw]=v.soilWetRaw; this[DevicesTable.disabledAt]=v.disabledAt; this[DevicesTable.createdAt]=v.createdAt }
private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.from(v: RefreshTokenRecord) { this[RefreshTokensTable.id]=v.id; this[RefreshTokensTable.userId]=v.userId; this[RefreshTokensTable.tokenHash]=v.tokenHash; this[RefreshTokensTable.expiresAt]=v.expiresAt; this[RefreshTokensTable.createdAt]=v.createdAt; this[RefreshTokensTable.revokedAt]=v.revokedAt; this[RefreshTokensTable.replacedById]=v.replacedById }
private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.from(v: AlertRuleRecord) { this[AlertRulesTable.id]=v.id; this[AlertRulesTable.plantId]=v.plantId; this[AlertRulesTable.type]=v.type; this[AlertRulesTable.threshold]=v.threshold; this[AlertRulesTable.requiredDurationSeconds]=v.requiredDurationSeconds; this[AlertRulesTable.recoveryDurationSeconds]=v.recoveryDurationSeconds; this[AlertRulesTable.enabled]=v.enabled; this[AlertRulesTable.createdAt]=v.createdAt; this[AlertRulesTable.updatedAt]=v.updatedAt }
private fun ResultRow.toUser() = UserRecord(this[UsersTable.id],this[UsersTable.email],this[UsersTable.passwordHash],this[UsersTable.createdAt])
private fun ResultRow.toPlant() = PlantRecord(this[PlantsTable.id],this[PlantsTable.userId],this[PlantsTable.name],this[PlantsTable.species],this[PlantsTable.imageUrl],this[PlantsTable.createdAt],this[PlantsTable.archivedAt])
private fun ResultRow.toDevice() = DeviceRecord(this[DevicesTable.id],this[DevicesTable.plantId],this[DevicesTable.name],this[DevicesTable.tokenHash],this[DevicesTable.firmwareVersion],this[DevicesTable.lastSeenAt],this[DevicesTable.soilDryRaw],this[DevicesTable.soilWetRaw],this[DevicesTable.disabledAt],this[DevicesTable.createdAt])
private fun ResultRow.toMeasurement(): MeasurementRecord { val m=NewMeasurement(this[MeasurementsTable.deviceId],this[MeasurementsTable.sequence],this[MeasurementsTable.measuredAt],this[MeasurementsTable.receivedAt],this[MeasurementsTable.soilMoistureRaw],this[MeasurementsTable.soilMoisturePercent],this[MeasurementsTable.airTemperatureCelsius],this[MeasurementsTable.airHumidityPercent],this[MeasurementsTable.lightRaw],this[MeasurementsTable.extra].toString()); return MeasurementRecord(this[MeasurementsTable.id],m) }
private fun ResultRow.toRefreshToken() = RefreshTokenRecord(this[RefreshTokensTable.id],this[RefreshTokensTable.userId],this[RefreshTokensTable.tokenHash],this[RefreshTokensTable.expiresAt],this[RefreshTokensTable.createdAt],this[RefreshTokensTable.revokedAt],this[RefreshTokensTable.replacedById])
private fun ResultRow.toAlertRule() = AlertRuleRecord(this[AlertRulesTable.id],this[AlertRulesTable.plantId],this[AlertRulesTable.type],this[AlertRulesTable.threshold],this[AlertRulesTable.requiredDurationSeconds],this[AlertRulesTable.recoveryDurationSeconds],this[AlertRulesTable.enabled],this[AlertRulesTable.createdAt],this[AlertRulesTable.updatedAt])
