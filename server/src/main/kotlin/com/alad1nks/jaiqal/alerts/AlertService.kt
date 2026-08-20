package com.alad1nks.jaiqal.alerts

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.users.UserApiException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class AlertService(private val dataSource: DataSource, private val clock: Clock = Clock.systemUTC()) {
    fun rules(userId: UUID, plantId: UUID): List<AlertRuleResponse> = connection { c ->
        requirePlant(c, userId, plantId)
        c.prepareStatement("SELECT id,type,threshold,required_duration_seconds,recovery_duration_seconds,enabled FROM alert_rules WHERE plant_id=? ORDER BY type").use { s ->
            s.setObject(1, plantId); s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.rule()) } }
        }
    }

    fun putRules(userId: UUID, plantId: UUID, request: PutAlertRulesRequest): List<AlertRuleResponse> = transaction { c ->
        requirePlant(c, userId, plantId)
        validateAlertRules(request)
        val retained = request.rules.map { it.type.name }.toSet()
        request.rules.forEach { rule ->
            c.prepareStatement("""INSERT INTO alert_rules(id,plant_id,type,threshold,required_duration_seconds,recovery_duration_seconds,enabled,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(plant_id,type) DO UPDATE SET threshold=excluded.threshold,required_duration_seconds=excluded.required_duration_seconds,recovery_duration_seconds=excluded.recovery_duration_seconds,enabled=excluded.enabled,updated_at=excluded.updated_at""").use { s ->
                val now = OffsetDateTime.now(clock); s.setObject(1, UUID.randomUUID()); s.setObject(2, plantId); s.setString(3, rule.type.name)
                s.setDouble(4, rule.threshold!!); s.setLong(5, rule.requiredDurationSeconds); s.setLong(6, rule.recoveryDurationSeconds)
                s.setBoolean(7, rule.enabled); s.setObject(8, now); s.setObject(9, now); s.executeUpdate()
            }
        }
        if (retained.isEmpty()) c.prepareStatement("DELETE FROM alert_rules WHERE plant_id=?").use { it.setObject(1, plantId); it.executeUpdate() }
        else c.prepareStatement("DELETE FROM alert_rules WHERE plant_id=? AND type <> ALL(?)").use { s ->
            s.setObject(1, plantId); s.setArray(2, c.createArrayOf("varchar", retained.toTypedArray())); s.executeUpdate()
        }
        c.commit()
        rules(userId, plantId)
    }

    fun alerts(userId: UUID, plantId: UUID): List<AlertEventResponse> = connection { c ->
        requirePlant(c, userId, plantId)
        c.prepareStatement("SELECT id,type,status,triggered_at,recovered_at,acknowledged_at,last_observed_at FROM alert_events WHERE plant_id=? ORDER BY triggered_at DESC LIMIT 500").use { s ->
            s.setObject(1, plantId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(AlertEventResponse(
                        rs.getObject("id", UUID::class.java).toString(), AlertType.valueOf(rs.getString("type")), AlertStatus.valueOf(rs.getString("status")),
                        rs.getObject("triggered_at", OffsetDateTime::class.java).toString(), rs.getObject("recovered_at", OffsetDateTime::class.java)?.toString(),
                        rs.getObject("acknowledged_at", OffsetDateTime::class.java)?.toString(), rs.getObject("last_observed_at", OffsetDateTime::class.java).toString(),
                    ))
                }
            }
        }
    }

    fun acknowledge(userId: UUID, plantId: UUID, alertId: UUID): AlertEventResponse = transaction { c ->
        requirePlant(c, userId, plantId)
        val changed = c.prepareStatement("UPDATE alert_events SET acknowledged_at=COALESCE(acknowledged_at,?) WHERE id=? AND plant_id=?").use { s ->
            s.setObject(1, OffsetDateTime.now(clock)); s.setObject(2, alertId); s.setObject(3, plantId); s.executeUpdate()
        }
        if (changed == 0) notFound(); c.commit()
        alerts(userId, plantId).first { it.id == alertId.toString() }
    }

    fun evaluatePlant(plantId: UUID, observedAt: OffsetDateTime = OffsetDateTime.now(clock)) = transaction { c ->
        val sample = latestSample(c, plantId)
        c.prepareStatement("SELECT id,type,threshold,required_duration_seconds,recovery_duration_seconds FROM alert_rules WHERE plant_id=? AND enabled=true FOR UPDATE").use { s ->
            s.setObject(1, plantId); s.executeQuery().use { rs -> while (rs.next()) {
                val id = rs.getObject("id", UUID::class.java); val type = AlertType.valueOf(rs.getString("type")); val threshold = rs.getDouble("threshold")
                val activeId = activeAlert(c, plantId, type); val old = state(c, id, activeId != null)
                val met = when (type) {
                    AlertType.LOW_SOIL_MOISTURE -> sample?.soil?.let { it < threshold } ?: false
                    AlertType.HIGH_TEMPERATURE -> sample?.temperature?.let { it > threshold } ?: false
                    AlertType.LOW_TEMPERATURE -> sample?.temperature?.let { it < threshold } ?: false
                    AlertType.DEVICE_OFFLINE -> sample == null || java.time.Duration.between(sample.lastSeen, observedAt).seconds >= threshold.toLong()
                }
                val result = AlertEngine.evaluate(old, met, observedAt, rs.getLong("required_duration_seconds"), rs.getLong("recovery_duration_seconds"))
                saveState(c, id, result.state, observedAt)
                when (result.transition) {
                    AlertTransition.OPEN -> open(c, plantId, id, type, observedAt, threshold)
                    AlertTransition.CLOSE -> activeId?.let { close(c, it, type, observedAt) }
                    AlertTransition.NONE -> activeId?.let { touch(c, it, observedAt) }
                }
            } }
        }
        c.commit()
    }

    fun plantsWithRules(): List<UUID> = connection { c -> c.createStatement().executeQuery("SELECT DISTINCT plant_id FROM alert_rules WHERE enabled=true").use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } } }

    private data class Sample(val soil: Double?, val temperature: Double?, val lastSeen: OffsetDateTime)
    private fun latestSample(c: Connection, plant: UUID): Sample? = c.prepareStatement("""SELECT m.soil_moisture_percent,m.air_temperature_celsius,d.last_seen_at FROM devices d LEFT JOIN device_latest_state l ON l.device_id=d.id LEFT JOIN measurements m ON m.device_id=l.device_id AND m.id=l.measurement_id WHERE d.plant_id=? AND d.disabled_at IS NULL ORDER BY d.last_seen_at DESC NULLS LAST LIMIT 1""").use { s ->
        s.setObject(1, plant); s.executeQuery().use { r -> if (r.next() && r.getObject(3) != null) Sample(r.getObject(1) as Double?, r.getObject(2) as Double?, r.getObject(3, OffsetDateTime::class.java)) else null }
    }
    private fun state(c: Connection, rule: UUID, active: Boolean) = c.prepareStatement("SELECT condition_since,recovery_since FROM alert_rule_state WHERE rule_id=?").use { s -> s.setObject(1, rule); s.executeQuery().use { r -> if (r.next()) AlertEvaluationState(r.getObject(1,OffsetDateTime::class.java),r.getObject(2,OffsetDateTime::class.java),active) else AlertEvaluationState(active=active) } }
    private fun saveState(c: Connection, rule: UUID, state: AlertEvaluationState, now: OffsetDateTime) = c.prepareStatement("INSERT INTO alert_rule_state(rule_id,condition_since,recovery_since,updated_at) VALUES(?,?,?,?) ON CONFLICT(rule_id) DO UPDATE SET condition_since=excluded.condition_since,recovery_since=excluded.recovery_since,updated_at=excluded.updated_at").use { s -> s.setObject(1,rule);s.setObject(2,state.conditionSince);s.setObject(3,state.recoverySince);s.setObject(4,now);s.executeUpdate();Unit }
    private fun activeAlert(c: Connection, plant: UUID, type: AlertType): UUID? = c.prepareStatement("SELECT id FROM alert_events WHERE plant_id=? AND type=? AND status='ACTIVE'").use { s -> s.setObject(1,plant);s.setString(2,type.name);s.executeQuery().use { r -> if(r.next()) r.getObject(1,UUID::class.java) else null } }
    private fun open(c: Connection, plant: UUID, rule: UUID, type: AlertType, now: OffsetDateTime, threshold: Double) {
        val event = UUID.randomUUID(); c.prepareStatement("INSERT INTO alert_events(id,plant_id,rule_id,type,status,triggered_at,last_observed_at,details) VALUES(?,?,?,?,'ACTIVE',?,?,?::jsonb)").use { s -> s.setObject(1,event);s.setObject(2,plant);s.setObject(3,rule);s.setString(4,type.name);s.setObject(5,now);s.setObject(6,now);s.setString(7,"{\"threshold\":$threshold}");s.executeUpdate() }
        enqueue(c,event,"opened",type,now)
    }
    private fun close(c: Connection, event: UUID, type: AlertType, now: OffsetDateTime) { c.prepareStatement("UPDATE alert_events SET status='RECOVERED',recovered_at=?,last_observed_at=? WHERE id=? AND status='ACTIVE'").use { s ->s.setObject(1,now);s.setObject(2,now);s.setObject(3,event);s.executeUpdate() }; enqueue(c,event,"recovered",type,now) }
    private fun touch(c: Connection,event:UUID,now:OffsetDateTime)=c.prepareStatement("UPDATE alert_events SET last_observed_at=? WHERE id=?").use{s->s.setObject(1,now);s.setObject(2,event);s.executeUpdate();Unit}
    private fun enqueue(c: Connection,event:UUID,action:String,type:AlertType,now:OffsetDateTime)=c.prepareStatement("INSERT INTO notification_outbox(alert_event_id,channel,payload,status,attempts,available_at,created_at,notification_key) VALUES(?,'LOG',?::jsonb,'PENDING',0,?,?,?) ON CONFLICT(notification_key) DO NOTHING").use{s->s.setObject(1,event);s.setString(2,Json.encodeToString(mapOf("eventId" to event.toString(),"action" to action,"type" to type.name)));s.setObject(3,now);s.setObject(4,now);s.setString(5,"$event:$action:LOG");s.executeUpdate();Unit}
    private fun requirePlant(c:Connection,user:UUID,plant:UUID){ if(c.prepareStatement("SELECT 1 FROM plants WHERE id=? AND user_id=? AND archived_at IS NULL").use{s->s.setObject(1,plant);s.setObject(2,user);s.executeQuery().use{it.next()}}.not()) notFound() }
    private fun java.sql.ResultSet.rule()=AlertRuleResponse(getObject("id",UUID::class.java).toString(),AlertType.valueOf(getString("type")),getObject("threshold") as Double?,getLong("required_duration_seconds"),getLong("recovery_duration_seconds"),getBoolean("enabled"))
    private fun notFound():Nothing=throw UserApiException(404,"NOT_FOUND","Plant or alert was not found")
    private fun <T> connection(block:(Connection)->T):T=dataSource.connection.use(block)
    private fun <T> transaction(block:(Connection)->T):T=dataSource.connection.use { c -> try { block(c) } catch(e:Throwable){c.rollback();throw e} }
}

internal fun validateAlertRules(request: PutAlertRulesRequest) {
    if (request.rules.size > AlertType.entries.size) {
        alertValidationError(
            "INVALID_ALERT_RULE_COUNT",
            "At most ${AlertType.entries.size} alert rules are allowed",
        )
    }
    if (request.rules.map { it.type }.distinct().size != request.rules.size) {
        alertValidationError("DUPLICATE_ALERT_TYPE", "Each alert type may occur only once")
    }
    request.rules.forEach(::validateAlertRule)
}

private fun validateAlertRule(rule: PutAlertRuleRequest) {
    val threshold = rule.threshold
    if (
        threshold == null ||
        !threshold.isFinite() ||
        (rule.type == AlertType.LOW_SOIL_MOISTURE && threshold !in 0.0..100.0) ||
        (rule.type == AlertType.DEVICE_OFFLINE && threshold <= 0)
    ) {
        alertValidationError("INVALID_THRESHOLD", "Threshold is invalid for this alert type")
    }
    if (rule.requiredDurationSeconds !in 0..2_592_000 || rule.recoveryDurationSeconds !in 0..2_592_000) {
        alertValidationError("INVALID_DURATION", "Durations must be between 0 and 2592000 seconds")
    }
}

private fun alertValidationError(code: String, message: String): Nothing =
    throw UserApiException(400, code, message)
