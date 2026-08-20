package com.alad1nks.jaiqal.infrastructure.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecurityEventLogTest {
    @Test
    fun `audit payload is versioned JSON with only allowlisted fields`() {
        val event = SecurityAuditEvent(
            action = SecurityAuditAction.ROTATE_DEVICE_TOKEN,
            result = SecurityAuditResult.SUCCESS,
            target = SecurityAuditTarget.DEVICE,
            actorUserId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            resourceId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            requestId = "safe-request_123",
        )

        val message = event.toStructuredMessage()
        val payload = Json.parseToJsonElement(message).jsonObject

        assertEquals(
            setOf("eventType", "schemaVersion", "action", "result", "target", "actorUserId", "resourceId", "requestId"),
            payload.keys,
        )
        assertEquals("SECURITY_AUDIT", payload.getValue("eventType").jsonPrimitive.content)
        assertEquals("1", payload.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("safe-request_123", payload.getValue("requestId").jsonPrimitive.content)
        listOf("token", "authorization", "firebaseUid", "email", "requestBody").forEach { forbidden ->
            assertFalse(payload.keys.any { it.equals(forbidden, ignoreCase = true) })
        }
    }

    @Test
    fun `absent identifiers are omitted instead of accepting placeholder text`() {
        val payload = Json.parseToJsonElement(
            SecurityAuditEvent(
                SecurityAuditAction.AUTHENTICATION,
                SecurityAuditResult.REJECTED,
                SecurityAuditTarget.USER_API,
            ).toStructuredMessage(),
        ).jsonObject

        assertEquals(setOf("eventType", "schemaVersion", "action", "result", "target"), payload.keys)
    }
}
