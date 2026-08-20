package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.CapacityMonitoringConfig
import com.alad1nks.jaiqal.infrastructure.security.securityEventMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.sql.DataSource

data class DatabaseCapacitySnapshot(
    val measurementsRows: Long,
    val measurementsBytes: Long,
    val databaseBytes: Long,
)

data class DatabaseCapacityAlert(val metric: String, val observed: Long, val threshold: Long)

class DatabaseCapacityMonitor(
    private val dataSource: DataSource,
    private val config: CapacityMonitoringConfig,
    private val log: Logger = LoggerFactory.getLogger("DatabaseCapacityMonitor"),
) {
    fun check() {
        val snapshot = readSnapshot()
        log.info(
            "database_capacity measurementsRows={} measurementsBytes={} databaseBytes={}",
            snapshot.measurementsRows,
            snapshot.measurementsBytes,
            snapshot.databaseBytes,
        )
        evaluate(snapshot, config).forEach { alert ->
            log.warn(alert.toStructuredMessage())
        }
    }

    private fun readSnapshot(): DatabaseCapacitySnapshot = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.queryTimeout = 5
            statement.executeQuery(
                """SELECT
                    COALESCE((
                        SELECT SUM(COALESCE(statistics.n_live_tup, 0))
                        FROM pg_partition_tree('measurements'::regclass) partition_tree
                        LEFT JOIN pg_stat_user_tables statistics ON statistics.relid = partition_tree.relid
                        WHERE partition_tree.isleaf
                    ), 0),
                    COALESCE((
                        SELECT SUM(pg_total_relation_size(partition_tree.relid))
                        FROM pg_partition_tree('measurements'::regclass) partition_tree
                        WHERE partition_tree.isleaf
                    ), 0),
                    pg_database_size(current_database())""",
            ).use { rows ->
                check(rows.next()) { "Database capacity query returned no row" }
                DatabaseCapacitySnapshot(rows.getLong(1), rows.getLong(2), rows.getLong(3))
            }
        }
    }

    companion object {
        fun evaluate(snapshot: DatabaseCapacitySnapshot, config: CapacityMonitoringConfig): List<DatabaseCapacityAlert> = buildList {
            if (snapshot.measurementsRows >= config.measurementsWarnRows) {
                add(DatabaseCapacityAlert("measurements_rows", snapshot.measurementsRows, config.measurementsWarnRows))
            }
            if (snapshot.measurementsBytes >= config.measurementsWarnBytes) {
                add(DatabaseCapacityAlert("measurements_bytes", snapshot.measurementsBytes, config.measurementsWarnBytes))
            }
            if (snapshot.databaseBytes >= config.databaseWarnBytes) {
                add(DatabaseCapacityAlert("database_bytes", snapshot.databaseBytes, config.databaseWarnBytes))
            }
        }
    }
}

internal fun DatabaseCapacityAlert.toStructuredMessage(): String =
    securityEventMessage(
        eventType = "SECURITY_CAPACITY_ALERT",
        fields = linkedMapOf(
            "metric" to metric,
            "observed" to observed,
            "threshold" to threshold,
        ),
    )
