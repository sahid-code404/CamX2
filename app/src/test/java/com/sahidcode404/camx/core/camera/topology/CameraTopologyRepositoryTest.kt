package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTopologyRepositoryTest {
    private val firstEnvironment = CameraEnvironmentFingerprint("environment:first")
    private val secondEnvironment = CameraEnvironmentFingerprint("environment:second")

    @Test
    fun newerReconciliationRejectsOlderPublicationInSameEnvironment() {
        val repository = CameraTopologyRepository()
        val stalePermit = repository.beginReconciliation(firstEnvironment)
        val currentPermit = repository.beginReconciliation(firstEnvironment)
        assertFalse(repository.publish(topology(firstEnvironment, 10L), stalePermit))
        assertTrue(repository.publish(topology(firstEnvironment, 20L), currentPermit))
        assertFalse(repository.publish(topology(firstEnvironment, 30L), currentPermit))
        assertEquals(20L, repository.topology.value?.generatedAtElapsedRealtimeNs)
    }

    @Test
    fun environmentSwitchClearsAndRejectsPriorWork() {
        val repository = CameraTopologyRepository()
        val firstPermit = repository.beginReconciliation(firstEnvironment)
        assertTrue(repository.publish(topology(firstEnvironment, 10L), firstPermit))
        val secondPermit = repository.beginReconciliation(secondEnvironment)
        assertNull(repository.topology.value)
        assertFalse(repository.publish(topology(firstEnvironment, 30L), firstPermit))
        assertTrue(repository.publish(topology(secondEnvironment, 40L), secondPermit))
        assertEquals(secondEnvironment, repository.topology.value?.environment)
    }

    @Test
    fun freshPermitAcceptsElapsedRealtimeRegressionAfterReboot() {
        val cached = topology(firstEnvironment, Long.MAX_VALUE)
        val repository = CameraTopologyRepository(cached)
        val freshBootPermit = repository.beginReconciliation(firstEnvironment)
        assertTrue(repository.publish(topology(firstEnvironment, 1L), freshBootPermit))
        assertEquals(1L, repository.topology.value?.generatedAtElapsedRealtimeNs)
    }

    private fun topology(environment: CameraEnvironmentFingerprint, timestamp: Long) =
        CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(
                CameraEvidenceSnapshot(
                    source = CameraRouteSource.JAVA_PUBLIC,
                    environment = environment,
                    evidence = listOf(
                        CameraMetadataEvidence(
                            source = CameraRouteSource.JAVA_PUBLIC,
                            transportId = CameraTransportId("opaque"),
                        ),
                    ),
                    completedAtElapsedRealtimeNs = timestamp,
                ),
            ),
            generatedAtElapsedRealtimeNs = timestamp,
        )
}
