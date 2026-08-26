package com.sahidcode404.camx.core.camera.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiscoveryMetadataBudgetTest {
    @Test
    fun `default discovery pressure is three java plus one native lane`() {
        val budget = DiscoveryMetadataBudget()
        assertEquals(3, budget.javaLanes)
        assertEquals(1, budget.nativeLanes)
        assertEquals(4, budget.maximumEffectivePressure)
    }

    @Test
    fun `combined metadata pressure above hard bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryMetadataBudget(javaLanes = 4, nativeLanes = 1)
        }
    }
}
