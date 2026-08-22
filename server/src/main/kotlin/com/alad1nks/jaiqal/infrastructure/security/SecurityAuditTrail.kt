package com.alad1nks.jaiqal.infrastructure.security

import org.slf4j.LoggerFactory
import java.util.UUID

enum class SecurityAuditAction {
    AUTHENTICATION,
    RATE_LIMIT,
    PROVISION_USER,
    DELETE_ACCOUNT,
    CLAIM_DEVICE,
    ROTATE_DEVICE_TOKEN,
    UPDATE_DEVICE_CALIBRATION,
    UPDATE_ALERT_RULES,
    ACKNOWLEDGE_ALERT,
    QUARANTINE_DEVICE,
    RESTORE_DEVICE,
}

enum class SecurityAuditResult { SUCCESS, REJECTED, FAILURE }

enum class SecurityAuditTarget { USER_API, DEVICE_API, READINESS, DEVICE, PLANT, ALERT, UNKNOWN }

data class SecurityAuditEvent(
    val action: SecurityAuditAction,
    val result: SecurityAuditResult,
    val target: SecurityAuditTarget,
    val actorUserId: UUID? = null,
    val resourceId: UUID? = null,
    val requestId: String? = null,
)

fun interface SecurityAuditTrail {
    fun record(event: SecurityAuditEvent)

    companion object {
        fun logging(): SecurityAuditTrail = Slf4jSecurityAuditTrail
    }
}

private object Slf4jSecurityAuditTrail : SecurityAuditTrail {
    private val log = LoggerFactory.getLogger("SECURITY_AUDIT")

    override fun record(event: SecurityAuditEvent) {
        log.info(event.toStructuredMessage())
    }
}

internal fun SecurityAuditEvent.toStructuredMessage(): String =
    securityEventMessage(
        eventType = "SECURITY_AUDIT",
        fields = linkedMapOf(
            "action" to action.name,
            "result" to result.name,
            "target" to target.name,
            "actorUserId" to actorUserId?.toString(),
            "resourceId" to resourceId?.toString(),
            "requestId" to requestId,
        )
    )
