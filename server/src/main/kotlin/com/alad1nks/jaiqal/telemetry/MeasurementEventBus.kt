package com.alad1nks.jaiqal.telemetry

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MeasurementEventBus : MeasurementEventPublisher {
    private val events = MutableSharedFlow<MeasurementReceived>(extraBufferCapacity = 256)
    val updates: SharedFlow<MeasurementReceived> = events
    override fun publish(event: MeasurementReceived) { events.tryEmit(event) }
}
