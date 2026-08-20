package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.TelemetryRetentionConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import javax.sql.DataSource

class TelemetryRetentionWorker(
    private val dataSource: DataSource,
    private val config: TelemetryRetentionConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val log: Logger = LoggerFactory.getLogger("TelemetryRetentionWorker"),
) {
    fun runOnce(): Int {
        val cutoff = OffsetDateTime.now(clock).minusDays(config.retentionDays)
        val result = runTelemetryRetentionBatches(config) { deleteBatch(cutoff) }
        if (result.deletedMeasurements > 0) {
            log.info(
                "telemetry_retention deletedMeasurements={} cutoff={} moreEligibleRowsPossible={}",
                result.deletedMeasurements,
                cutoff,
                result.moreEligibleRowsPossible,
            )
        }
        return result.deletedMeasurements
    }

    private fun deleteBatch(cutoff: OffsetDateTime): Int = dataSource.connection.use { connection ->
        try {
            val deleted = connection.prepareStatement(DELETE_BATCH_SQL).use { statement ->
                statement.queryTimeout = 30
                statement.setObject(1, cutoff)
                statement.setInt(2, config.batchSize)
                statement.executeUpdate()
            }
            connection.commit()
            deleted
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private companion object {
        val DELETE_BATCH_SQL = """
            WITH victims AS (
                SELECT measurement.device_id, measurement.id
                FROM measurements measurement
                LEFT JOIN device_latest_state latest
                  ON latest.device_id = measurement.device_id
                 AND latest.measurement_id = measurement.id
                WHERE measurement.received_at < ?
                  AND latest.device_id IS NULL
                ORDER BY measurement.received_at, measurement.device_id, measurement.id
                LIMIT ?
                FOR UPDATE OF measurement SKIP LOCKED
            )
            DELETE FROM measurements measurement
            USING victims
            WHERE measurement.device_id = victims.device_id
              AND measurement.id = victims.id
        """.trimIndent()
    }
}

internal data class TelemetryRetentionResult(
    val deletedMeasurements: Int,
    val moreEligibleRowsPossible: Boolean,
)

internal fun runTelemetryRetentionBatches(
    config: TelemetryRetentionConfig,
    deleteBatch: () -> Int,
): TelemetryRetentionResult {
    var deleted = 0
    repeat(config.maxBatchesPerRun) {
        val batchDeleted = deleteBatch()
        require(batchDeleted in 0..config.batchSize) { "Retention batch result exceeds configured batch size" }
        deleted += batchDeleted
        if (batchDeleted < config.batchSize) {
            return TelemetryRetentionResult(deleted, moreEligibleRowsPossible = false)
        }
    }
    return TelemetryRetentionResult(deleted, moreEligibleRowsPossible = true)
}
