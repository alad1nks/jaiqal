package com.alad1nks.jaiqal.feature.plants.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlantRealtimeDataSourceTest {
    @Test
    fun streamUsesActualPlantEndpoint() {
        assertEquals(
            "https://api.example.test/api/v1/plants/plant-a/stream",
            plantRealtimeUrl("https://api.example.test/", "plant-a"),
        )
    }

    @Test
    fun onlyMeasurementEventsAreDecodedFromSharedContract() {
        val update = decodePlantTelemetryEvent(
            "measurement",
            """{"plantId":"plant-a","deviceId":"device-a","measurementId":42}""",
        )

        assertEquals("plant-a", update?.plantId)
        assertEquals(42L, update?.measurementId)
        assertNull(decodePlantTelemetryEvent(null, null))
        assertNull(decodePlantTelemetryEvent("other", "{}"))
    }
}
