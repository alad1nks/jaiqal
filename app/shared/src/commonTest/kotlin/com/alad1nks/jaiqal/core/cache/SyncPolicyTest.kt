package com.alad1nks.jaiqal.core.cache

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncPolicyTest {
    @Test
    fun policiesMatchTheOfflineContract() {
        assertEquals(ReadStrategy.CACHE_FIRST_THEN_REFRESH, SyncPolicies.plantList.readStrategy)
        assertEquals(ReadStrategy.CACHE_FIRST_THEN_REFRESH, SyncPolicies.plantDetails.readStrategy)
        assertEquals(ReadStrategy.CACHE_FIRST_THEN_STREAM, SyncPolicies.latestMeasurement.readStrategy)
        assertEquals(ReadStrategy.NETWORK_FIRST_WITH_CACHE_FALLBACK, SyncPolicies.history.readStrategy)
        assertEquals(ReadStrategy.CACHE_FIRST_THEN_REFRESH, SyncPolicies.alerts.readStrategy)
        assertEquals(WriteStrategy.SERVER_FIRST_THEN_CACHE, SyncPolicies.mutation.writeStrategy)
    }
}
