package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.LensFacing
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitRescanTopologyPreservationTest {
    private val environment = CameraEnvironmentFingerprint("d3-rescan-preserve")

    @Test
    fun `explicit rescan retains current topology while publishing new evidence`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot("cached")),
            generatedAtElapsedRealtimeNs = 1L,
        )
        val repository = CameraTopologyRepository(previous)
        var pass = 0
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { emit ->
                    pass += 1
                    emit(listOf(snapshot(if (pass == 1) "cached" else "new")))
                },
            ),
            clockNanos = { 10L + pass },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()
        assertEquals(setOf("cached"), repository.topology.value!!.routes.map { it.openCameraId.value }.toSet())

        assertEquals(
            ReconciliationRequestResult.STARTED,
            reconciler.requestReconciliation(preserveCurrentTopology = true),
        )
        assertEquals(
            setOf("cached", "new"),
            repository.topology.value!!.routes.map { it.openCameraId.value }.toSet(),
        )
        reconciler.close()
    }

    private fun snapshot(id: String) = CameraEvidenceSnapshot(
        source = CameraRouteSource.JAVA_PUBLIC,
        environment = environment,
        evidence = listOf(
            CameraMetadataEvidence(
                source = CameraRouteSource.JAVA_PUBLIC,
                transportId = CameraTransportId(id),
                facing = LensFacing.BACK,
                focalLengthsMillimetres = listOf(4f),
                sensorPhysicalWidthMillimetres = 6f,
                sensorPhysicalHeightMillimetres = 4.5f,
                capabilities = CameraCapabilities(),
            ),
        ),
        completedAtElapsedRealtimeNs = 1L,
    )
}
