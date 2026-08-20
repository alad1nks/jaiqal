package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.DriverManager

fun interface DatabaseReadiness {
    suspend fun isReady(): Boolean
}

class CachedDatabaseReadiness(
    private val delegate: DatabaseReadiness,
    cacheTtlMilliseconds: Long,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : DatabaseReadiness {
    private val cacheTtlNanos = cacheTtlMilliseconds * NANOS_PER_MILLISECOND
    private val checkMutex = Mutex()

    @Volatile
    private var cachedResult: CachedResult? = null

    init {
        require(cacheTtlMilliseconds in 1..MAX_CACHE_TTL_MILLISECONDS) {
            "Readiness cache TTL must be between 1 and $MAX_CACHE_TTL_MILLISECONDS milliseconds"
        }
    }

    override suspend fun isReady(): Boolean {
        cachedResult.currentValue(monotonicNanos())?.let { return it }
        return checkMutex.withLock {
            cachedResult.currentValue(monotonicNanos())?.let { return@withLock it }
            delegate.isReady().also { ready ->
                cachedResult = CachedResult(ready, monotonicNanos())
            }
        }
    }

    private fun CachedResult?.currentValue(nowNanos: Long): Boolean? {
        val cached = this ?: return null
        val ageNanos = nowNanos - cached.checkedAtNanos
        return cached.ready.takeIf { ageNanos >= 0 && ageNanos < cacheTtlNanos }
    }

    private data class CachedResult(val ready: Boolean, val checkedAtNanos: Long)

    companion object {
        const val MAX_CACHE_TTL_MILLISECONDS = 5_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
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
