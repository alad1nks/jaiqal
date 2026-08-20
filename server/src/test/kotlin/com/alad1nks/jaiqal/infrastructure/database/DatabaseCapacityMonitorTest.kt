package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.CapacityMonitoringConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseCapacityMonitorTest {
    private val config = CapacityMonitoringConfig(
        measurementsWarnRows = 100,
        measurementsWarnBytes = 200,
        databaseWarnBytes = 300,
    )

    @Test
    fun `emits an alert for every reached capacity threshold`() {
        val alerts = DatabaseCapacityMonitor.evaluate(
            DatabaseCapacitySnapshot(100, 250, 350),
            config,
        )

        assertEquals(listOf("measurements_rows", "measurements_bytes", "database_bytes"), alerts.map { it.metric })
    }

    @Test
    fun `stays quiet below capacity thresholds`() {
        assertEquals(
            emptyList(),
            DatabaseCapacityMonitor.evaluate(DatabaseCapacitySnapshot(99, 199, 299), config),
        )
    }

    @Test
    fun `capacity alert payload keeps numeric values`() {
        val payload = Json.parseToJsonElement(
            DatabaseCapacityAlert("database_bytes", 350, 300).toStructuredMessage(),
        ).jsonObject

        assertEquals("SECURITY_CAPACITY_ALERT", payload.getValue("eventType").jsonPrimitive.content)
        assertEquals(350, payload.getValue("observed").jsonPrimitive.content.toLong())
        assertEquals(300, payload.getValue("threshold").jsonPrimitive.content.toLong())
        assertEquals(false, payload.getValue("observed").jsonPrimitive.isString)
    }
}
