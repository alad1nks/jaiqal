package com.alad1nks.jaiqal.infrastructure.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

internal object UsersTable : Table("users") {
    val id = javaUUID("id")
    val email = varchar("email", 320).nullable()
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object UserIdentitiesTable : Table("user_identities") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val provider = varchar("provider", 50)
    val externalSubject = varchar("external_subject", 255)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object PlantsTable : Table("plants") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val name = varchar("name", 255)
    val species = varchar("species", 255).nullable()
    val imageUrl = varchar("image_url", 2048).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val archivedAt = timestampWithTimeZone("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object DevicesTable : Table("devices") {
    val id = javaUUID("id")
    val plantId = javaUUID("plant_id").nullable()
    val name = varchar("name", 255)
    val tokenHash = varchar("token_hash", 64)
    val firmwareVersion = varchar("firmware_version", 100).nullable()
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val soilDryRaw = integer("soil_dry_raw").nullable()
    val soilWetRaw = integer("soil_wet_raw").nullable()
    val disabledAt = timestampWithTimeZone("disabled_at").nullable()
    val quarantineUntil = timestampWithTimeZone("quarantine_until").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object MeasurementsTable : Table("measurements") {
    val id = long("id").autoIncrement()
    val deviceId = javaUUID("device_id")
    val sequence = long("sequence")
    val measuredAt = timestampWithTimeZone("measured_at")
    val receivedAt = timestampWithTimeZone("received_at")
    val soilMoistureRaw = integer("soil_moisture_raw").nullable()
    val soilMoisturePercent = double("soil_moisture_percent").nullable()
    val airTemperatureCelsius = double("air_temperature_celsius").nullable()
    val airHumidityPercent = double("air_humidity_percent").nullable()
    val lightRaw = integer("light_raw").nullable()
    val extra = jsonb<JsonElement>("extra", Json.Default)
    override val primaryKey = PrimaryKey(deviceId, id)
}

internal object DeviceLatestStateTable : Table("device_latest_state") {
    val deviceId = javaUUID("device_id")
    val measurementId = long("measurement_id")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(deviceId)
}

internal object AlertRulesTable : Table("alert_rules") {
    val id = javaUUID("id")
    val plantId = javaUUID("plant_id")
    val type = varchar("type", 50)
    val threshold = double("threshold").nullable()
    val requiredDurationSeconds = long("required_duration_seconds")
    val recoveryDurationSeconds = long("recovery_duration_seconds")
    val enabled = bool("enabled")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object NotificationOutboxTable : Table("notification_outbox") {
    val id = long("id").autoIncrement()
    val alertEventId = javaUUID("alert_event_id")
    val channel = varchar("channel", 30)
    val payload = jsonb<JsonElement>("payload", Json.Default)
    val status = varchar("status", 20)
    val attempts = integer("attempts")
    val availableAt = timestampWithTimeZone("available_at")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
