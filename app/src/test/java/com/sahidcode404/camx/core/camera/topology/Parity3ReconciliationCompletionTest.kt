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

class Parity3ReconciliationCompletionTest {
    private val environment = CameraEnvironmentFingerprint("parity3-rescan-completion")

    @Test
    fun `completion aware rescan reports complete after successful bounded providers`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot("main")),
            generatedAtElapsedRealtimeNs = 1L,
        )
        val repository = CameraTopologyRepository(previous)
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { emit ->
                    emit(listOf(snapshot("main")))
                },
            ),
            clockNanos = { 2L },
            dispatcher = Dispatchers.Unconfined,
        )
        reconciler.startAfterFirstFrame()
        var completion: ReconciliationCompletion? = null

        assertEquals(
            ReconciliationRequestResult.STARTED,
            reconciler.requestReconciliationWithCompletion(
                preserveCurrentTopology = true,
                onFinished = { completion = it },
            ),
        )

        assertEquals(ReconciliationCompletion.COMPLETE, completion)
        reconciler.close()
    }

    @Test
    fun `provider infrastructure failure reports incomplete and keeps previous topology`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot("main")),
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
                    if (pass == 1) {
                        emit(listOf(snapshot("main")))
                    } else {
                        throw IllegalStateException("temporary service failure")
                    }
                },
            ),
            clockNanos = { 2L + pass },
            dispatcher = Dispatchers.Unconfined,
        )
        reconciler.startAfterFirstFrame()
        var completion: ReconciliationCompletion? = null

        assertEquals(
            ReconciliationRequestResult.STARTED,
            reconciler.requestReconciliationWithCompletion(
                preserveCurrentTopology = true,
                onFinished = { completion = it },
            ),
        )

        assertEquals(ReconciliationCompletion.INCOMPLETE, completion)
        assertEquals(
            setOf("main"),
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
                focalLengthsMillimetres = listOf(5f),
                sensorPhysicalWidthMillimetres = 6f,
                sensorPhysicalHeightMillimetres = 4.5f,
                capabilities = CameraCapabilities(),
            ),
        ),
        completedAtElapsedRealtimeNs = 1L,
    )
}
