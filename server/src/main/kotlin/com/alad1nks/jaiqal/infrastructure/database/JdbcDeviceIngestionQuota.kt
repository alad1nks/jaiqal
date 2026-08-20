package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.TelemetryConfig
import com.alad1nks.jaiqal.telemetry.DeviceIngestionQuota
import com.alad1nks.jaiqal.telemetry.IngestionQuotaDecision
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditResult
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTarget
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** PostgreSQL-backed fixed-window quota. The row lock makes consumption atomic across server replicas. */
class JdbcDeviceIngestionQuota(
    private val dataSource: DataSource,
    private val config: TelemetryConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val securityAuditTrail: SecurityAuditTrail = SecurityAuditTrail.logging(),
) : DeviceIngestionQuota {
    override fun tryConsume(deviceId: UUID, measurementCount: Int): IngestionQuotaDecision {
        require(measurementCount > 0)
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        var quarantineStarted = false
        val decision = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """INSERT INTO device_ingestion_quotas(device_id, window_started_at, measurements_used, updated_at)
                       VALUES (?, ?, 0, ?) ON CONFLICT (device_id) DO NOTHING""",
                ).use { statement ->
                    statement.setObject(1, deviceId)
                    statement.setObject(2, now)
                    statement.setObject(3, now)
                    statement.executeUpdate()
                }

                val current = connection.prepareStatement(
                    "SELECT window_started_at, measurements_used FROM device_ingestion_quotas WHERE device_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setObject(1, deviceId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "Device ingestion quota row was not created" }
                        rows.getObject("window_started_at", OffsetDateTime::class.java) to rows.getInt("measurements_used")
                    }
                }

                val securityState = connection.prepareStatement(
                    """SELECT anomaly_window_started_at, quota_breached_windows,
                              last_breached_quota_window_at, quarantine_until
                       FROM devices WHERE id = ? FOR UPDATE""",
                ).use { statement ->
                    statement.setObject(1, deviceId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "Authenticated device no longer exists" }
                        DeviceSecurityState(
                            anomalyWindowStartedAt = rows.getObject("anomaly_window_started_at", OffsetDateTime::class.java),
                            breachedWindows = rows.getInt("quota_breached_windows"),
                            lastBreachedQuotaWindowAt = rows.getObject("last_breached_quota_window_at", OffsetDateTime::class.java),
                            quarantineUntil = rows.getObject("quarantine_until", OffsetDateTime::class.java),
                        )
                    }
                }

                securityState.quarantineUntil?.takeIf(now::isBefore)?.let { quarantineUntil ->
                    connection.commit()
                    return@use IngestionQuotaDecision(
                        allowed = false,
                        retryAfterSeconds = Duration.between(now, quarantineUntil).seconds.coerceAtLeast(1),
                        quarantined = true,
                    )
                }

                val expiresAt = current.first.plusSeconds(config.quotaPeriodSeconds)
                val windowStartedAt = if (!now.isBefore(expiresAt)) now else current.first
                val measurementsUsed = if (!now.isBefore(expiresAt)) 0 else current.second
                val allowed = measurementsUsed.toLong() + measurementCount <= config.quotaMaxMeasurements.toLong()

                if (allowed) {
                    connection.prepareStatement(
                        """UPDATE device_ingestion_quotas
                           SET window_started_at = ?, measurements_used = ?, updated_at = ?
                           WHERE device_id = ?""",
                    ).use { statement ->
                        statement.setObject(1, windowStartedAt)
                        statement.setInt(2, measurementsUsed + measurementCount)
                        statement.setObject(3, now)
                        statement.setObject(4, deviceId)
                        check(statement.executeUpdate() == 1)
                    }
                } else if (securityState.lastBreachedQuotaWindowAt != windowStartedAt) {
                    val observationExpired = securityState.anomalyWindowStartedAt == null ||
                        !now.isBefore(securityState.anomalyWindowStartedAt.plusSeconds(config.anomalyWindowSeconds))
                    val anomalyWindowStartedAt = if (observationExpired) now else securityState.anomalyWindowStartedAt
                    val breachedWindows = if (observationExpired) 1 else securityState.breachedWindows + 1
                    val quarantineUntil = if (breachedWindows >= config.anomalyBreachWindows) {
                        now.plusSeconds(config.quarantineSeconds)
                    } else {
                        null
                    }
                    connection.prepareStatement(
                        """UPDATE devices
                           SET anomaly_window_started_at = ?, quota_breached_windows = ?,
                               last_breached_quota_window_at = ?, quarantined_at = ?,
                               quarantine_until = ?
                           WHERE id = ?""",
                    ).use { statement ->
                        statement.setObject(1, if (quarantineUntil == null) anomalyWindowStartedAt else null)
                        statement.setInt(2, if (quarantineUntil == null) breachedWindows else 0)
                        statement.setObject(3, windowStartedAt)
                        statement.setObject(4, quarantineUntil?.let { now })
                        statement.setObject(5, quarantineUntil)
                        statement.setObject(6, deviceId)
                        check(statement.executeUpdate() == 1)
                    }
                    quarantineStarted = quarantineUntil != null
                }
                connection.commit()

                IngestionQuotaDecision(
                    allowed = allowed,
                    retryAfterSeconds = when {
                        allowed -> 0
                        quarantineStarted -> config.quarantineSeconds
                        else -> Duration.between(now, windowStartedAt.plusSeconds(config.quotaPeriodSeconds))
                            .seconds.coerceAtLeast(1)
                    },
                    quarantined = quarantineStarted,
                )
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
        if (quarantineStarted) {
            securityAuditTrail.record(
                SecurityAuditEvent(
                    action = SecurityAuditAction.QUARANTINE_DEVICE,
                    result = SecurityAuditResult.SUCCESS,
                    target = SecurityAuditTarget.DEVICE,
                    resourceId = deviceId,
                ),
            )
        }
        return decision
    }
}

private data class DeviceSecurityState(
    val anomalyWindowStartedAt: OffsetDateTime?,
    val breachedWindows: Int,
    val lastBreachedQuotaWindowAt: OffsetDateTime?,
    val quarantineUntil: OffsetDateTime?,
)
