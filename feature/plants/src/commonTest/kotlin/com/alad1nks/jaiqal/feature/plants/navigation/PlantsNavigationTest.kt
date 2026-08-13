package com.alad1nks.jaiqal.feature.plants.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class PlantsNavigationTest {
    @Test
    fun plantDeepLinkMatchesRegisteredPlatformScheme() {
        assertEquals("jaiqal://plants/plant-a", plantDeepLink("plant-a"))
    }
}
