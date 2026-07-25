package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DriverManager

fun interface DatabaseReadiness {
    suspend fun isReady(): Boolean
}

class JdbcDatabaseReadiness(
    private val config: DatabaseConfig,
) : DatabaseReadiness {
    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = DATABASE_TIMEOUT_SECONDS
                    statement.executeQuery("SELECT 1").use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                }
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val DATABASE_TIMEOUT_SECONDS = 2
    }
}
