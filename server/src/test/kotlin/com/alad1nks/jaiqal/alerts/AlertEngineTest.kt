package com.alad1nks.jaiqal.alerts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.OffsetDateTime

class AlertEngineTest {
    private val start = OffsetDateTime.parse("2026-01-01T00:00:00Z")

    @Test fun `transient condition does not open duration rule`() {
        val pending = AlertEngine.evaluate(AlertEvaluationState(), true, start, 60, 30)
        assertEquals(AlertTransition.NONE, pending.transition)
        val recovered = AlertEngine.evaluate(pending.state, false, start.plusSeconds(30), 60, 30)
        assertEquals(AlertTransition.NONE, recovered.transition)
        assertNull(recovered.state.conditionSince)
    }

    @Test fun `condition opens once after required duration and remains deduplicated`() {
        val pending = AlertEngine.evaluate(AlertEvaluationState(), true, start, 60, 30)
        val opened = AlertEngine.evaluate(pending.state, true, start.plusSeconds(60), 60, 30)
        assertEquals(AlertTransition.OPEN, opened.transition)
        val repeated = AlertEngine.evaluate(opened.state, true, start.plusSeconds(120), 60, 30)
        assertEquals(AlertTransition.NONE, repeated.transition)
        assertEquals(true, repeated.state.active)
    }

    @Test fun `recovery must remain healthy for configured duration`() {
        val active = AlertEvaluationState(active = true)
        val pending = AlertEngine.evaluate(active, false, start, 0, 45)
        assertEquals(AlertTransition.NONE, pending.transition)
        val interrupted = AlertEngine.evaluate(pending.state, true, start.plusSeconds(20), 0, 45)
        assertNull(interrupted.state.recoverySince)
        val pendingAgain = AlertEngine.evaluate(interrupted.state, false, start.plusSeconds(30), 0, 45)
        val closed = AlertEngine.evaluate(pendingAgain.state, false, start.plusSeconds(75), 0, 45)
        assertEquals(AlertTransition.CLOSE, closed.transition)
    }

    @Test fun `zero durations transition immediately`() {
        val opened = AlertEngine.evaluate(AlertEvaluationState(), true, start, 0, 0)
        assertEquals(AlertTransition.OPEN, opened.transition)
        assertEquals(AlertTransition.CLOSE, AlertEngine.evaluate(opened.state, false, start, 0, 0).transition)
    }
}
