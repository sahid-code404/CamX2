package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAdvertisedCameraEvidenceBackendTest {
    private val environment = CameraEnvironmentFingerprint("camx-107-test")

    @Test
    fun `startup seed depth performs no advertised reads`() = runTest {
        val source = FakeSource(listOf("opaque-a"), mutableMapOf("opaque-a" to record("opaque-a")))
        val report = backend(source).discoverReport(DiscoveryDepth.STARTUP_SEED)

        assertEquals(0, source.reads.size)
        assertTrue(report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.isEmpty())
    }

    @Test
    fun `one rear public route retains bounded enriched metadata`() = runTest {
        val source = FakeSource(
            listOf("rear-token"),
            mutableMapOf("rear-token" to record("rear-token", facing = LensFacing.BACK)),
        )
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)
        val item = report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.single()

        assertEquals("rear-token", item.transportId.value)
        assertEquals(LensFacing.BACK, item.facing)
        assertEquals(listOf(4.2f), item.focalLengthsMillimetres)
        assertEquals(IntSize(4000, 3000), item.activeArray)
        assertEquals(1, item.capabilities.previewStreams.size)
        assertEquals(1, item.capabilities.fpsRanges.size)
        assertEquals(1, item.capabilities.rawSizes.size)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `front and rear public routes are both preserved without id role semantics`() = runTest {
        val source = FakeSource(
            listOf("0", "9"),
            mutableMapOf(
                "0" to record("0", facing = LensFacing.FRONT),
                "9" to record("9", facing = LensFacing.BACK),
            ),
        )
        val evidence = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)
            .snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence

        assertEquals(setOf("0", "9"), evidence.map { it.transportId.value }.toSet())
        assertEquals(LensFacing.FRONT, evidence.single { it.transportId.value == "0" }.facing)
        assertEquals(LensFacing.BACK, evidence.single { it.transportId.value == "9" }.facing)
    }

    @Test
    fun `physical relationships publish before child metadata enrichment`() = runTest {
        val source = FakeSource(
            listOf("logical-x"),
            mutableMapOf(
                "logical-x" to record("logical-x", physicalIds = listOf("wide-member", "tele-member")),
                "wide-member" to record("wide-member", focal = 2.0f),
                "tele-member" to record("tele-member", focal = 8.0f),
            ),
        )
        val emissions = ArrayList<JavaAdvertisedEvidenceReport>()
        backend(source).discoverIncrementally(DiscoveryDepth.ADVERTISED) { emissions += it }

        val firstPhysical = emissions.firstNotNullOf { it.snapshotFor(CameraRouteSource.JAVA_PHYSICAL) }
        assertEquals(setOf("wide-member", "tele-member"), firstPhysical.evidence.mapNotNull { it.physicalId?.value }.toSet())
        assertTrue(firstPhysical.evidence.all { it.capabilities.previewStreams.isEmpty() })
        assertEquals(setOf("wide-member", "tele-member"), source.enrichedReads.filterNot { it == "logical-x" }.toSet())
    }

    @Test
    fun `inaccessible physical member keeps relationship without fabricated capabilities`() = runTest {
        val source = FakeSource(
            listOf("logical"),
            mutableMapOf("logical" to record("logical", physicalIds = listOf("hidden-member"))),
            failReads = setOf("hidden-member"),
        )
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)
        val physical = report.snapshotFor(CameraRouteSource.JAVA_PHYSICAL)!!.evidence.single()

        assertEquals("logical", physical.transportId.value)
        assertEquals("hidden-member", physical.physicalId!!.value)
        assertTrue(physical.capabilities.previewStreams.isEmpty())
        assertTrue(physical.capabilities.rawSizes.isEmpty())
        assertTrue(report.failures.any {
            it.kind == JavaAdvertisedEvidenceFailureKind.PHYSICAL_CHARACTERISTICS_UNAVAILABLE
        })
    }

    @Test
    fun `one broken public id does not erase valid public evidence`() = runTest {
        val source = FakeSource(
            listOf("broken", "valid"),
            mutableMapOf("valid" to record("valid")),
            failReads = setOf("broken"),
        )
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)

        assertEquals(
            listOf("valid"),
            report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.map { it.transportId.value },
        )
        assertTrue(report.failures.any {
            it.kind == JavaAdvertisedEvidenceFailureKind.CHARACTERISTICS_UNAVAILABLE
        })
    }

    @Test
    fun `missing optional optics remain valid evidence`() = runTest {
        val sparse = record("sparse").copy(
            focalLengthsMillimetres = emptyList(),
            sensorPhysicalWidthMillimetres = null,
            sensorPhysicalHeightMillimetres = null,
            activeArray = null,
            pixelArray = null,
            apertureValues = emptyList(),
            colorFilterArrangement = null,
        )
        val source = FakeSource(listOf("sparse"), mutableMapOf("sparse" to sparse))
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)

        assertEquals(1, report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.size)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `reordered ids and duplicate ids produce identical evidence order`() = runTest {
        val records = mutableMapOf(
            "alpha" to record("alpha"),
            "beta" to record("beta", focal = 7.0f),
        )
        val first = backend(FakeSource(listOf("beta", "alpha", "beta"), HashMap(records)))
            .discoverReport(DiscoveryDepth.ADVERTISED)
        val second = backend(FakeSource(listOf("alpha", "beta"), HashMap(records)))
            .discoverReport(DiscoveryDepth.ADVERTISED)

        assertEquals(
            first.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence,
            second.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence,
        )
    }

    @Test
    fun `public id count overflow fails closed before any characteristics read`() = runTest {
        val ids = (0..AUX_MAX_PUBLIC_IDS).map { "opaque-$it" }
        val source = FakeSource(ids, mutableMapOf())
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)

        assertTrue(report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.isEmpty())
        assertTrue(source.reads.isEmpty())
        assertEquals(
            JavaAdvertisedEvidenceFailureKind.PUBLIC_ID_LIMIT_EXCEEDED,
            report.failures.single().kind,
        )
    }

    @Test
    fun `physical id count overflow preserves logical route and skips member reads`() = runTest {
        val ids = (0..AUX_MAX_PHYSICAL_IDS_PER_LOGICAL).map { "member-$it" }
        val source = FakeSource(
            listOf("logical"),
            mutableMapOf("logical" to record("logical", physicalIds = ids)),
        )
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)

        assertEquals(1, report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.size)
        assertTrue(source.reads.all { it == "logical" })
        assertTrue(report.snapshotFor(CameraRouteSource.JAVA_PHYSICAL) == null)
        assertTrue(report.failures.any {
            it.kind == JavaAdvertisedEvidenceFailureKind.PHYSICAL_ID_LIMIT_EXCEEDED
        })
    }

    @Test
    fun `metadata list overflow rejects only offending route`() = runTest {
        val tooManyFocals = (1..AUX_MAX_FOCAL_LENGTHS + 1).map(Int::toFloat)
        val source = FakeSource(
            listOf("oversized", "valid"),
            mutableMapOf(
                "oversized" to record("oversized").copy(focalLengthsMillimetres = tooManyFocals),
                "valid" to record("valid"),
            ),
        )
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)

        assertEquals(
            listOf("valid"),
            report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence.map { it.transportId.value },
        )
        assertTrue(report.failures.any {
            it.kind == JavaAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED
        })
    }

    @Test
    fun `java metadata work reaches exactly configured three lanes without blocking`() = runTest {
        var active = 0
        var maximum = 0
        val threeEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val records = (0 until 6).associate { index -> "id-$index" to record("id-$index") }.toMutableMap()
        val source = object : JavaAdvertisedCameraMetadataSource {
            override fun advertisedIds(): List<String> = records.keys.toList()

            override suspend fun read(id: String): JavaAdvertisedCameraRecord? = records[id]

            override suspend fun readMinimal(id: String): JavaAdvertisedCameraRecord? {
                active += 1
                maximum = maxOf(maximum, active)
                if (maximum == DEFAULT_JAVA_METADATA_LANES) threeEntered.complete(Unit)
                release.await()
                active -= 1
                return records[id]?.minimalCopy()
            }
        }

        val discovery = launch { backend(source).discoverReport(DiscoveryDepth.ADVERTISED) }
        threeEntered.await()

        assertEquals(DEFAULT_JAVA_METADATA_LANES, active)
        assertEquals(DEFAULT_JAVA_METADATA_LANES, maximum)

        release.complete(Unit)
        discovery.join()
        assertEquals(0, active)
    }

    @Test
    fun `published evidence and failures reject mutation`() = runTest {
        val source = FakeSource(listOf("rear"), mutableMapOf("rear" to record("rear")))
        val report = backend(source).discoverReport(DiscoveryDepth.ADVERTISED)
        val evidence = report.snapshotFor(CameraRouteSource.JAVA_PUBLIC)!!.evidence

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (evidence as MutableList<CameraMetadataEvidence>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (report.failures as MutableList<JavaAdvertisedEvidenceFailure>).clear()
        }
    }

    private fun backend(source: JavaAdvertisedCameraMetadataSource) =
        AndroidAdvertisedCameraEvidenceBackend(
            environment = environment,
            clockNanos = { 123L },
            source = source,
            metadataBudget = DiscoveryMetadataBudget(),
        )

    private fun record(
        id: String,
        facing: LensFacing = LensFacing.BACK,
        focal: Float = 4.2f,
        physicalIds: List<String> = emptyList(),
    ) = JavaAdvertisedCameraRecord(
        queriedId = id,
        facing = facing,
        focalLengthsMillimetres = listOf(focal),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        apertureValues = listOf(1.8f),
        colorFilterArrangement = 0,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1920, 1080),
                    33_333_333L,
                ),
            ),
            fpsRanges = listOf(CameraFpsCapability(15, 30)),
            rawSizes = listOf(IntSize(4000, 3000)),
        ),
        physicalIds = physicalIds,
    )

    private class FakeSource(
        private val ids: List<String>,
        private val records: MutableMap<String, JavaAdvertisedCameraRecord>,
        private val failReads: Set<String> = emptySet(),
    ) : JavaAdvertisedCameraMetadataSource {
        val reads = ArrayList<String>()
        val enrichedReads = ArrayList<String>()

        override fun advertisedIds(): List<String> = ids

        override suspend fun readMinimal(id: String): JavaAdvertisedCameraRecord? {
            reads += id
            if (id in failReads) throw IllegalStateException("inaccessible")
            return records[id]?.minimalCopy()
        }

        override suspend fun read(id: String): JavaAdvertisedCameraRecord? {
            reads += id
            enrichedReads += id
            if (id in failReads) throw IllegalStateException("inaccessible")
            return records[id]
        }
    }
}
