package com.alad1nks.jaiqal.notifications

import java.time.OffsetDateTime
import java.util.UUID

data class NotificationOutboxRecord(
    val id: Long? = null, val alertEventId: UUID, val channel: String, val payload: String,
    val status: String = "PENDING", val attempts: Int = 0, val availableAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
)

interface NotificationOutboxRepository {
    fun enqueue(notification: NotificationOutboxRecord): NotificationOutboxRecord
}
