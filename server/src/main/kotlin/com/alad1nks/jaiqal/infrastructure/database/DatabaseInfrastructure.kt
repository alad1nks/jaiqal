package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.MigrationDatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

class DatabaseInfrastructure private constructor(
    val dataSource: HikariDataSource,
    val database: Database,
) : AutoCloseable {
    fun verifyRuntimeHasNoDdlPrivileges() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT
                        (role.rolsuper OR role.rolcreatedb OR role.rolcreaterole) AS elevated,
                        EXISTS (
                            SELECT 1
                            FROM unnest(current_schemas(false)) schema_name
                            WHERE has_schema_privilege(current_user, schema_name, 'CREATE')
                        ) AS can_create,
                        EXISTS (
                            SELECT 1
                            FROM pg_class object
                            JOIN pg_namespace namespace ON namespace.oid = object.relnamespace
                            WHERE namespace.nspname = ANY(current_schemas(false))
                              AND object.relkind IN ('r', 'p', 'S', 'v', 'm')
                              AND pg_has_role(current_user, object.relowner, 'MEMBER')
                        ) AS owns_objects
                    FROM pg_roles role
                    WHERE role.rolname = current_user
                    """.trimIndent(),
                ).use { row ->
                    check(row.next()) { "Could not inspect runtime database role" }
                    check(!row.getBoolean("elevated")) {
                        "Runtime database role must not have elevated role attributes"
                    }
                    check(!row.getBoolean("can_create")) {
                        "Runtime database role must not have CREATE on the application schema search path"
                    }
                    check(!row.getBoolean("owns_objects")) {
                        "Runtime database role must not own or inherit ownership of application schema objects"
                    }
                }
            }
            connection.rollback()
        }
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

object DatabaseMigrator {
    fun migrate(config: MigrationDatabaseConfig) =
        Flyway.configure()
            .dataSource(
                config.url,
                config.user,
                config.password,
            )
            .load()
            .migrate()
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
