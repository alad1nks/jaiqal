package com.alad1nks.jaiqal.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class PushTokenRegistrarTest {
    @Test
    fun unavailableRegistrarNeverRequestsOrProducesAToken() = runTest {
        val registrar = UnavailablePushTokenRegistrar()

        assertEquals(PushPermissionResult.UNSUPPORTED, registrar.requestPermission())
        assertNull(registrar.currentToken())
        registrar.syncToken()
    }
}
