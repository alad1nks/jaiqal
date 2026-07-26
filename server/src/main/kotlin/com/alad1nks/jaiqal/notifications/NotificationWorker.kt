package com.alad1nks.jaiqal.notifications

import com.alad1nks.jaiqal.config.AlertConfig
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.min

fun interface NotificationSender { fun send(notification: OutboxNotification) }
data class OutboxNotification(val id: Long, val channel: String, val payload: String, val attempts: Int)

class LoggingNotificationSender : NotificationSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun send(notification: OutboxNotification) {
        log.info("Development notification delivered outboxId={} channel={} payload={}", notification.id, notification.channel, notification.payload)
    }
}

/** PostgreSQL SKIP LOCKED prevents two worker instances from claiming the same row. */
class NotificationWorker(
    private val dataSource: DataSource,
    private val sender: NotificationSender,
    private val config: AlertConfig,
    private val workerId: String = UUID.randomUUID().toString(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun runOnce(): Int {
        val claimed = claim()
        claimed.forEach(::deliverWithAdvisoryLock)
        return claimed.size
    }

    private fun deliverWithAdvisoryLock(notification: OutboxNotification) {
        dataSource.connection.use { lockConnection ->
            val locked = lockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { s ->
                s.setLong(1, notification.id); s.executeQuery().use { it.next() && it.getBoolean(1) }
            }
            // A stale lease may be reclaimed while its original sender is still running.
            // The session advisory lock prevents a concurrent second delivery in that case.
            if (!locked) return
            try {
                runCatching { sender.send(notification) }
                    .onSuccess { complete(notification.id) }
                    .onFailure { retry(notification, it) }
            } finally {
                lockConnection.prepareStatement("SELECT pg_advisory_unlock(?)").use { s ->
                    s.setLong(1, notification.id); s.execute()
                }
            }
        }
    }

    private fun claim(): List<OutboxNotification> = transaction { c ->
        val now = OffsetDateTime.now(clock)
        val rows = c.prepareStatement("""SELECT id,channel,payload::text,attempts FROM notification_outbox
            WHERE (status='PENDING' AND available_at<=?) OR (status='PROCESSING' AND locked_at<?)
            ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT ?""").use { s ->
            s.setObject(1, now); s.setObject(2, now.minusMinutes(5)); s.setInt(3, config.outboxBatchSize)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(OutboxNotification(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4)+1)) } }
        }
        c.prepareStatement("UPDATE notification_outbox SET status='PROCESSING',locked_at=?,locked_by=?,attempts=attempts+1 WHERE id=?").use { s ->
            rows.forEach { s.setObject(1,now);s.setString(2,workerId);s.setLong(3,it.id);s.addBatch() };s.executeBatch()
        }
        c.commit(); rows
    }
    private fun complete(id:Long)=transaction { c -> c.prepareStatement("UPDATE notification_outbox SET status='COMPLETED',completed_at=?,locked_at=NULL,locked_by=NULL,last_error=NULL WHERE id=? AND status='PROCESSING' AND locked_by=?").use{s->s.setObject(1,OffsetDateTime.now(clock));s.setLong(2,id);s.setString(3,workerId);s.executeUpdate()};c.commit() }
    private fun retry(n:OutboxNotification,error:Throwable)=transaction { c ->
        val exponent = (n.attempts-1).coerceIn(0,30); val delay = min(config.outboxMaxBackoffSeconds, 1L shl exponent)
        c.prepareStatement("UPDATE notification_outbox SET status='PENDING',available_at=?,locked_at=NULL,locked_by=NULL,last_error=? WHERE id=? AND status='PROCESSING' AND locked_by=?").use{s->s.setObject(1,OffsetDateTime.now(clock).plusSeconds(delay));s.setString(2,(error.message?:error::class.simpleName.orEmpty()).take(4000));s.setLong(3,n.id);s.setString(4,workerId);s.executeUpdate()};c.commit()
    }
    private fun <T> transaction(block:(Connection)->T):T=dataSource.connection.use{c->try{block(c)}catch(e:Throwable){c.rollback();throw e}}
}
