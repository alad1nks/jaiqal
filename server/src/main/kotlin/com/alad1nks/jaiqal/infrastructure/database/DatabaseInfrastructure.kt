package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

class DatabaseInfrastructure private constructor(
    val dataSource: HikariDataSource,
    val database: Database,
) : AutoCloseable {
    fun migrate() {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    override fun close() = dataSource.close()

    companion object {
        fun create(config: DatabaseConfig): DatabaseInfrastructure {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 10
                minimumIdle = 1
                connectionTimeout = 5_000
                validationTimeout = 2_000
                isAutoCommit = false
                poolName = "jaiqal-postgres"
            }
            val dataSource = HikariDataSource(hikari)
            return DatabaseInfrastructure(dataSource, Database.connect(dataSource))
        }
    }
}

class DataSourceDatabaseReadiness(private val dataSource: DataSource) : DatabaseReadiness {
    override suspend fun isReady(): Boolean = runCatching {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 2
                statement.executeQuery("SELECT 1").use { it.next() && it.getInt(1) == 1 }
            }
        }
    }.getOrDefault(false)
}
