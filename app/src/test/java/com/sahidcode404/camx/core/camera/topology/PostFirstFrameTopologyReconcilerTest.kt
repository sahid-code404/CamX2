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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFirstFrameTopologyReconcilerTest {
    private val environment = CameraEnvironmentFingerprint("camx-107-post-frame")

    @Test
    fun `healthy backend publishes when another backend fails`() {
        val repository = CameraTopologyRepository()
        var failingCalls = 0
        var healthyCalls = 0
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider {
                    failingCalls += 1
                    throw IllegalStateException("simulated backend failure")
                },
                AdvertisedTopologyEvidenceProvider {
                    healthyCalls += 1
                    listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("healthy")))
                },
            ),
            clockNanos = { 100L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(1, failingCalls)
        assertEquals(1, healthyCalls)
        assertEquals(1L, repository.publicationCount())
        assertEquals(listOf("healthy"), repository.topology.value!!.routes.map { it.openCameraId.value })
        reconciler.close()
    }

    @Test
    fun `both backend failures publish empty current topology instead of stale cameras`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("stale"))),
            generatedAtElapsedRealtimeNs = 1L,
        )
        val repository = CameraTopologyRepository(previous)
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { error("java unavailable") },
                AdvertisedTopologyEvidenceProvider { error("ndk unavailable") },
            ),
            clockNanos = { 101L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(1L, repository.publicationCount())
        val topology = assertNotNull(repository.topology.value).let { repository.topology.value!! }
        assertTrue(topology.routes.isEmpty())
        assertTrue(topology.canonicalLenses.isEmpty())
        reconciler.close()
    }

    @Test
    fun `post first frame trigger is exactly once`() {
        val repository = CameraTopologyRepository()
        var calls = 0
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider {
                    calls += 1
                    listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("once")))
                },
            ),
            clockNanos = { 102L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()
        reconciler.startAfterFirstFrame()
        reconciler.startAfterFirstFrame()

        assertEquals(1, calls)
        assertEquals(1L, repository.publicationCount())
        reconciler.close()
    }

    @Test
    fun `closed reconciler never starts metadata work`() {
        val repository = CameraTopologyRepository()
        var calls = 0
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider {
                    calls += 1
                    emptyList()
                },
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.close()
        reconciler.startAfterFirstFrame()

        assertEquals(0, calls)
        assertEquals(0L, repository.publicationCount())
    }

    @Test
    fun `pathological evidence count fails closed before publication`() {
        val repository = CameraTopologyRepository()
        val oversized = (0..CameraTopologyResolver.MAX_TOTAL_EVIDENCE).map { index ->
            evidence("opaque-$index")
        }
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider {
                    listOf(
                        CameraEvidenceSnapshot(
                            source = CameraRouteSource.JAVA_PUBLIC,
                            environment = environment,
                            evidence = oversized,
                            completedAtElapsedRealtimeNs = 1L,
                        ),
                    )
                },
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(0L, repository.publicationCount())
        assertEquals(null, repository.topology.value)
        reconciler.close()
    }

    @Test
    fun `publication timestamp uses bounded supplied clock`() {
        val repository = CameraTopologyRepository()
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider {
                    listOf(snapshot(CameraRouteSource.NDK_ADVERTISED, evidence("ndk", CameraRouteSource.NDK_ADVERTISED)))
                },
            ),
            clockNanos = { -5L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(0L, repository.topology.value!!.generatedAtElapsedRealtimeNs)
        reconciler.close()
    }

    private fun snapshot(
        source: CameraRouteSource,
        vararg evidence: CameraMetadataEvidence,
    ) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence.map { it.copy(source = source) },
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun evidence(
        id: String,
        source: CameraRouteSource = CameraRouteSource.JAVA_PUBLIC,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        capabilities = CameraCapabilities(),
    )
}
