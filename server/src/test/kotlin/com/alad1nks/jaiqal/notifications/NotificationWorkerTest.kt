package com.alad1nks.jaiqal.notifications

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NotificationWorkerTest {
    @Test
    fun `untyped exception maps to safe delivery failure without its message`() {
        val secret = "Bearer raw-provider-token"

        val code = notificationFailureCode(IllegalStateException(secret))

        assertEquals(NotificationFailureCode.DELIVERY_FAILED, code)
        assertFalse(code.name.contains(secret))
    }

    @Test
    fun `typed failure code survives safe exception wrapping`() {
        val secret = "https://user:password@provider.example/private"
        val failure = IllegalStateException(
            "Provider failed: $secret",
            NotificationDeliveryException(NotificationFailureCode.PROVIDER_UNAVAILABLE),
        )

        val code = notificationFailureCode(failure)

        assertEquals(NotificationFailureCode.PROVIDER_UNAVAILABLE, code)
        assertFalse(code.name.contains(secret))
    }
}
