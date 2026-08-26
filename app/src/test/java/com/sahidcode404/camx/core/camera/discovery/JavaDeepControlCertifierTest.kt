package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaDeepControlCertifierTest {
    private val environment = CameraEnvironmentFingerprint("java-deep-test")

    @Test
    fun `NDK deep valid candidate certifies through Java metadata`() = runTest {
        val certifier = certifier { JavaDeepMetadataRead.Success(record(it)) }

        val report = certifier.certify(listOf(valid("opaque-hidden")))

        assertEquals(JavaDeepCertificationKind.CERTIFIED, report.outcomes.single().kind)
        val evidence = report.snapshot.evidence.single()
        assertEquals(CameraRouteSource.JAVA_DEEP_PROBED, evidence.source)
        assertEquals("opaque-hidden", evidence.transportId.value)
        assertTrue(evidence.capabilities.previewStreams.all { it.type == PreviewStreamType.CAMERA2_PRIVATE })
        assertTrue(evidence.capabilities.fpsRanges.isNotEmpty())
        assertTrue(evidence.capabilities.rawSizes.isEmpty())
    }

    @Test
    fun `API23 certification path has no native runtime gate`() = runTest {
        var reads = 0
        val certifier = certifier { id ->
            reads += 1
            JavaDeepMetadataRead.Success(record(id))
        }

        val report = certifier.certify(listOf(valid("api23-queryable-hidden")))

        assertEquals(1, reads)
        assertEquals(JavaDeepCertificationKind.CERTIFIED, report.outcomes.single().kind)
    }

    @Test
    fun `NDK deep valid candidate can report Java id unavailable`() = runTest {
        val certifier = certifier { JavaDeepMetadataRead.NotFound }

        val report = certifier.certify(listOf(valid("hidden")))

        assertEquals(JavaDeepCertificationKind.JAVA_NOT_FOUND, report.outcomes.single().kind)
        assertTrue(report.snapshot.evidence.isEmpty())
    }

    @Test
    fun `NDK deep valid candidate can report Java access failure`() = runTest {
        val certifier = certifier { JavaDeepMetadataRead.AccessDenied }

        val report = certifier.certify(listOf(valid("hidden")))

        assertEquals(JavaDeepCertificationKind.JAVA_ACCESS_DENIED, report.outcomes.single().kind)
        assertTrue(report.snapshot.evidence.isEmpty())
    }

    @Test
    fun `missing private preview is rejected`() = runTest {
        val certifier = certifier {
            JavaDeepMetadataRead.Success(
                record(it).copy(capabilities = CameraCapabilities(fpsRanges = listOf(CameraFpsCapability(15, 30)))),
            )
        }

        val report = certifier.certify(listOf(valid("hidden")))

        assertEquals(JavaDeepCertificationKind.NO_PRIVATE_PREVIEW, report.outcomes.single().kind)
        assertTrue(report.snapshot.evidence.isEmpty())
    }

    @Test
    fun `missing orientation is rejected`() = runTest {
        val certifier = certifier { JavaDeepMetadataRead.Success(record(it).copy(sensorOrientationDegrees = null)) }

        val report = certifier.certify(listOf(valid("hidden")))

        assertEquals(JavaDeepCertificationKind.MISSING_ORIENTATION, report.outcomes.single().kind)
    }

    @Test
    fun `invalid NDK deep outcome never invokes Java certification`() = runTest {
        var reads = 0
        val certifier = certifier {
            reads += 1
            JavaDeepMetadataRead.Success(record(it))
        }
        val candidate = DeepAuxCandidate("hidden", DeepAuxWave.HOT)

        val report = certifier.certify(
            listOf(DeepAuxCandidateOutcome(candidate, DeepAuxOutcomeKind.SERVICE_ERROR)),
        )

        assertEquals(0, reads)
        assertTrue(report.outcomes.isEmpty())
        assertTrue(report.snapshot.evidence.isEmpty())
    }

    @Test
    fun `existing Java public route prevents duplicate deep certification`() = runTest {
        var reads = 0
        val certifier = certifier {
            reads += 1
            JavaDeepMetadataRead.Success(record(it))
        }
        val publicEvidence = CameraMetadataEvidence(
            source = CameraRouteSource.JAVA_PUBLIC,
            transportId = CameraTransportId("hidden"),
        )

        val report = certifier.certify(listOf(valid("hidden")), listOf(publicEvidence))

        assertEquals(0, reads)
        assertEquals(JavaDeepCertificationKind.ALREADY_REPRESENTED, report.outcomes.single().kind)
    }

    @Test
    fun `existing Java physical member prevents duplicate direct deep route`() = runTest {
        var reads = 0
        val certifier = certifier {
            reads += 1
            JavaDeepMetadataRead.Success(record(it))
        }
        val physicalEvidence = CameraMetadataEvidence(
            source = CameraRouteSource.JAVA_PHYSICAL,
            transportId = CameraTransportId("logical-parent"),
            physicalId = PhysicalCameraId("hidden-child"),
            logicalParentId = CameraTransportId("logical-parent"),
        )

        val report = certifier.certify(listOf(valid("hidden-child")), listOf(physicalEvidence))

        assertEquals(0, reads)
        assertEquals(JavaDeepCertificationKind.ALREADY_REPRESENTED, report.outcomes.single().kind)
    }

    @Test
    fun `Java certification never exceeds three concurrent metadata operations`() = runTest {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val certifier = JavaDeepControlCertifier(
            environment = environment,
            metadataBudget = DiscoveryMetadataBudget(),
            metadataSource = JavaDeepCameraMetadataSource { id ->
                val now = active.incrementAndGet()
                while (true) {
                    val previous = maximum.get()
                    if (now <= previous || maximum.compareAndSet(previous, now)) break
                }
                yield()
                active.decrementAndGet()
                JavaDeepMetadataRead.Success(record(id))
            },
            clockNanos = { 9L },
        )

        val report = certifier.certify((0 until 8).map { valid("opaque-$it") })

        assertEquals(8, report.outcomes.count { it.kind == JavaDeepCertificationKind.CERTIFIED })
        assertTrue(maximum.get() <= 3)
        assertEquals(4, DiscoveryMetadataBudget().maximumEffectivePressure)
    }

    @Test
    fun `early Java certification publishes before later Java chunk finishes`() = runTest {
        val reads = ArrayList<String>()
        var readsAtFirstEmission: Int? = null
        val certifier = certifier { id ->
            reads += id
            JavaDeepMetadataRead.Success(record(id))
        }

        certifier.certifyIncrementally((0 until 4).map { valid("candidate-$it") }, emptyList()) { report ->
            if (report.snapshot.evidence.isNotEmpty() && readsAtFirstEmission == null) {
                readsAtFirstEmission = reads.size
            }
        }

        assertEquals(4, reads.size)
        assertEquals(3, readsAtFirstEmission)
    }

    @Test
    fun `one Java metadata failure does not cancel peer candidate`() = runTest {
        val certifier = certifier { id ->
            if (id == "bad") JavaDeepMetadataRead.MetadataError
            else JavaDeepMetadataRead.Success(record(id))
        }

        val report = certifier.certify(listOf(valid("bad"), valid("good")))

        assertTrue(report.outcomes.any {
            it.candidate.transportId == "bad" && it.kind == JavaDeepCertificationKind.JAVA_METADATA_ERROR
        })
        assertTrue(report.outcomes.any {
            it.candidate.transportId == "good" && it.kind == JavaDeepCertificationKind.CERTIFIED
        })
        assertTrue(report.snapshot.evidence.any { it.transportId.value == "good" })
    }

    @Test
    fun `certification retains logical physical relationship hints without creating routes`() = runTest {
        val certifier = certifier {
            JavaDeepMetadataRead.Success(record(it).copy(physicalIds = listOf("child-b", "child-a")))
        }

        val report = certifier.certify(listOf(valid("logical-hidden")))

        assertEquals(listOf("child-a", "child-b"), report.outcomes.single().discoveredPhysicalIds)
        assertTrue(report.snapshot.evidence.single().logicalParentId == null)
    }

    private fun certifier(
        source: suspend (String) -> JavaDeepMetadataRead,
    ) = JavaDeepControlCertifier(
        environment = environment,
        metadataBudget = DiscoveryMetadataBudget(),
        metadataSource = JavaDeepCameraMetadataSource { id -> source(id) },
        clockNanos = { 7L },
    )

    private fun valid(id: String) = DeepAuxCandidateOutcome(
        candidate = DeepAuxCandidate(id, DeepAuxWave.HOT),
        outcome = DeepAuxOutcomeKind.VALID_METADATA,
    )

    private fun record(id: String) = JavaAdvertisedCameraRecord(
        queriedId = id,
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        apertureValues = emptyList(),
        colorFilterArrangement = null,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1280, 720),
                    33_333_333L,
                ),
            ),
            fpsRanges = listOf(CameraFpsCapability(15, 30), CameraFpsCapability(30, 30)),
            rawSizes = emptyList(),
        ),
        physicalIds = emptyList(),
    )
}
