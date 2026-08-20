package com.alad1nks.jaiqal.plugins

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SseConnectionLimiter(
    private val maxConnectionsPerUser: Int = 3,
    private val maxConnectionsPerIp: Int = 10,
) {
    private val lock = Any()
    private val connectionsByUser = mutableMapOf<UUID, Int>()
    private val connectionsByIp = mutableMapOf<String, Int>()

    init {
        require(maxConnectionsPerUser > 0)
        require(maxConnectionsPerIp > 0)
    }

    fun tryAcquire(userId: UUID, remoteAddress: String): Lease? = synchronized(lock) {
        val userConnections = connectionsByUser[userId] ?: 0
        val ipConnections = connectionsByIp[remoteAddress] ?: 0
        if (userConnections >= maxConnectionsPerUser || ipConnections >= maxConnectionsPerIp) {
            return@synchronized null
        }

        connectionsByUser[userId] = userConnections + 1
        connectionsByIp[remoteAddress] = ipConnections + 1
        Lease { release(userId, remoteAddress) }
    }

    private fun release(userId: UUID, remoteAddress: String) = synchronized(lock) {
        connectionsByUser.decrementOrRemove(userId)
        connectionsByIp.decrementOrRemove(remoteAddress)
    }

    private fun <K> MutableMap<K, Int>.decrementOrRemove(key: K) {
        val remaining = (this[key] ?: return) - 1
        if (remaining == 0) remove(key) else this[key] = remaining
    }

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
