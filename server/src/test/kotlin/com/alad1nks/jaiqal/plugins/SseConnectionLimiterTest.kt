package com.alad1nks.jaiqal.plugins

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SseConnectionLimiterTest {
    @Test
    fun enforcesUserAndIpCapsAndReleasesLeases() {
        val limiter = SseConnectionLimiter(maxConnectionsPerUser = 1, maxConnectionsPerIp = 2)
        val firstUser = UUID.randomUUID()
        val secondUser = UUID.randomUUID()
        val thirdUser = UUID.randomUUID()

        val first = assertNotNull(limiter.tryAcquire(firstUser, "192.0.2.10"))
        assertNull(limiter.tryAcquire(firstUser, "192.0.2.11"))
        val second = assertNotNull(limiter.tryAcquire(secondUser, "192.0.2.10"))
        assertNull(limiter.tryAcquire(thirdUser, "192.0.2.10"))

        first.close()
        first.close()
        assertNotNull(limiter.tryAcquire(thirdUser, "192.0.2.10")).close()
        second.close()
    }
}
