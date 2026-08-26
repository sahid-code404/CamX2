package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.LensFacing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                AdvertisedTopologyEvidenceProvider { _ ->
                    failingCalls += 1
                    throw IllegalStateException("simulated backend failure")
                },
                AdvertisedTopologyEvidenceProvider { emit ->
                    healthyCalls += 1
                    emit(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("healthy"))))
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `providers run concurrently rather than one after another`() = runTest {
        val repository = CameraTopologyRepository()
        val firstSuspended = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var firstResumed = false
        var secondRanWhileFirstSuspended = false
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { emit ->
                    emit(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("java"))))
                    firstSuspended.complete(Unit)
                    releaseFirst.await()
                    firstResumed = true
                },
                AdvertisedTopologyEvidenceProvider { emit ->
                    firstSuspended.await()
                    secondRanWhileFirstSuspended = !firstResumed && !releaseFirst.isCompleted
                    emit(listOf(snapshot(
                        CameraRouteSource.NDK_ADVERTISED,
                        evidence("ndk", CameraRouteSource.NDK_ADVERTISED),
                    )))
                    releaseFirst.complete(Unit)
                },
            ),
            clockNanos = { 100L },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        reconciler.startAfterFirstFrame()
        advanceUntilIdle()
        val observedConcurrentStart = secondRanWhileFirstSuspended

        if (!releaseFirst.isCompleted) releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertTrue(firstSuspended.isCompleted)
        assertTrue(observedConcurrentStart)
        assertTrue(firstResumed)
        assertEquals(2L, repository.publicationCount())
        assertEquals(setOf("java", "ndk"), repository.topology.value!!.routes.map { it.openCameraId.value }.toSet())
        reconciler.close()
    }

    @Test
    fun `one provider can publish incremental improvements before completion`() {
        val repository = CameraTopologyRepository()
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { emit ->
                    emit(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("first"))))
                    emit(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("second"))))
                },
            ),
            clockNanos = { 100L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(2L, repository.publicationCount())
        assertEquals(setOf("first", "second"), repository.topology.value!!.routes.map { it.openCameraId.value }.toSet())
        reconciler.close()
    }

    @Test
    fun `temporary failure of every backend preserves compatible cached topology`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("cached"))),
            generatedAtElapsedRealtimeNs = 1L,
        )
        val repository = CameraTopologyRepository(previous)
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { _ -> error("java temporarily unavailable") },
                AdvertisedTopologyEvidenceProvider { _ -> error("ndk temporarily unavailable") },
            ),
            clockNanos = { 101L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(0L, repository.publicationCount())
        assertEquals(previous, repository.topology.value)
        assertEquals(listOf("cached"), repository.topology.value!!.routes.map { it.openCameraId.value })
        reconciler.close()
    }

    @Test
    fun `successful full empty reconciliation may clear compatible cached topology`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("cached"))),
            generatedAtElapsedRealtimeNs = 1L,
        )
        val repository = CameraTopologyRepository(previous)
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { _ -> },
                AdvertisedTopologyEvidenceProvider { _ -> },
            ),
            clockNanos = { 101L },
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(1L, repository.publicationCount())
        assertTrue(repository.topology.value!!.routes.isEmpty())
        assertTrue(repository.topology.value!!.canonicalLenses.isEmpty())
        reconciler.close()
    }

    @Test
    fun `one successful empty provider plus one failed provider does not prove empty`() {
        val previous = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("cached"))),
            generatedAtElapsedRealtimeNs = 1L,
        )
        val repository = CameraTopologyRepository(previous)
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { _ -> },
                AdvertisedTopologyEvidenceProvider { _ -> error("ndk failure") },
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(0L, repository.publicationCount())
        assertEquals(previous, repository.topology.value)
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
                AdvertisedTopologyEvidenceProvider { emit ->
                    calls += 1
                    emit(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("once"))))
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
                AdvertisedTopologyEvidenceProvider { _ -> calls += 1 },
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.close()
        reconciler.startAfterFirstFrame()

        assertEquals(0, calls)
        assertEquals(0L, repository.publicationCount())
    }

    @Test
    fun `pathological evidence count is rejected without fabricated empty publication`() {
        val repository = CameraTopologyRepository()
        val oversized = (0..CameraTopologyResolver.MAX_TOTAL_EVIDENCE).map { index ->
            evidence("opaque-$index")
        }
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { emit ->
                    emit(listOf(
                        CameraEvidenceSnapshot(
                            source = CameraRouteSource.JAVA_PUBLIC,
                            environment = environment,
                            evidence = oversized,
                            completedAtElapsedRealtimeNs = 1L,
                        ),
                    ))
                },
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        reconciler.startAfterFirstFrame()

        assertEquals(0L, repository.publicationCount())
        assertNull(repository.topology.value)
        reconciler.close()
    }

    @Test
    fun `publication timestamp uses bounded supplied clock`() {
        val repository = CameraTopologyRepository()
        val reconciler = PostFirstFrameTopologyReconciler(
            environment = environment,
            repository = repository,
            providers = listOf(
                AdvertisedTopologyEvidenceProvider { emit ->
                    emit(listOf(snapshot(
                        CameraRouteSource.NDK_ADVERTISED,
                        evidence("ndk", CameraRouteSource.NDK_ADVERTISED),
                    )))
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
