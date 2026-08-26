package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Camx107TopologyReconciliationTest {
    private val environment = CameraEnvironmentFingerprint("camx-107-full")

    @Test
    fun `one rear`() {
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("rear", focal = 4.2f)))
        assertEquals(1, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(LensFacing.BACK, topology.canonicalLenses.single().facing)
        assertInvariants(topology)
    }

    @Test
    fun `front plus rear`() {
        val topology = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence("front", facing = LensFacing.FRONT, focal = 3.0f),
                evidence("rear", facing = LensFacing.BACK, focal = 4.2f),
            ),
        )
        assertEquals(2, topology.routes.size)
        assertEquals(setOf(LensFacing.FRONT, LensFacing.BACK), topology.canonicalLenses.map { it.facing }.toSet())
        assertInvariants(topology)
    }

    @Test
    fun `main plus ultrawide remain distinct without explicit relationship`() {
        assertIndependent("main", 4.2f, "ultrawide", 1.8f)
    }

    @Test
    fun `main plus tele remain distinct without explicit relationship`() {
        assertIndependent("main", 4.2f, "tele", 8.0f)
    }

    @Test
    fun `main ultrawide tele remain three lenses`() {
        val topology = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence("main", focal = 4.2f),
                evidence("uw", focal = 1.8f),
                evidence("tele", focal = 8.0f),
            ),
        )
        assertEquals(3, topology.routes.size)
        assertEquals(3, topology.canonicalLenses.size)
        assertInvariants(topology)
    }

    @Test
    fun `macro like independent lens remains separate`() {
        assertIndependent("main", 4.2f, "macro", 3.7f)
    }

    @Test
    fun `logical camera with multiple physical members preserves parent open semantics`() {
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("logical", focal = 4.2f)),
            snapshot(
                CameraRouteSource.JAVA_PHYSICAL,
                physical("logical", "wide-member", 2.0f),
                physical("logical", "tele-member", 8.0f),
            ),
        )
        assertEquals(3, topology.routes.size)
        val physicalRoutes = topology.routes.filter { it.physicalCameraId != null }
        assertEquals(2, physicalRoutes.size)
        assertTrue(physicalRoutes.all { it.openCameraId.value == "logical" })
        assertEquals(setOf("wide-member", "tele-member"), physicalRoutes.map { it.physicalCameraId!!.value }.toSet())
        assertFalse(topology.routes.any { it.openCameraId.value in setOf("wide-member", "tele-member") && it.physicalCameraId == null })
        assertInvariants(topology)
    }

    @Test
    fun `java and ndk exact compatible aliases reconcile one route with provenance`() {
        val java = evidence("same", source = CameraRouteSource.JAVA_PUBLIC)
        val ndk = java.copy(source = CameraRouteSource.NDK_ADVERTISED)
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, java),
            snapshot(CameraRouteSource.NDK_ADVERTISED, ndk),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.NDK_ADVERTISED), topology.routes.single().sources)
        assertInvariants(topology)
    }

    @Test
    fun `partial alias with missing optional metadata reconciles exact transport`() {
        val java = evidence("same")
        val ndk = CameraMetadataEvidence(
            source = CameraRouteSource.NDK_ADVERTISED,
            transportId = CameraTransportId("same"),
            facing = LensFacing.BACK,
        )
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, java),
            snapshot(CameraRouteSource.NDK_ADVERTISED, ndk),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(2, topology.routes.single().sources.size)
    }

    @Test
    fun `conflicting providers on one exact transport remain one profile`() {
        val java = evidence("same", focal = 4.2f)
        val ndk = evidence("same", source = CameraRouteSource.NDK_ADVERTISED, focal = 9.0f)
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, java),
            snapshot(CameraRouteSource.NDK_ADVERTISED, ndk),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(1, topology.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(
            setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.NDK_ADVERTISED),
            topology.routes.single().sources,
        )
        assertEquals(
            setOf(listOf(4.2f), listOf(9.0f)),
            topology.evidence.map { it.focalLengthsMillimetres }.toSet(),
        )
        assertInvariants(topology)
    }

    @Test
    fun `inaccessible physical member relationship does not fabricate independent open`() {
        val member = CameraMetadataEvidence(
            source = CameraRouteSource.JAVA_PHYSICAL,
            transportId = CameraTransportId("logical"),
            physicalId = PhysicalCameraId("member"),
            logicalParentId = CameraTransportId("logical"),
            facing = LensFacing.BACK,
        )
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PHYSICAL, member))
        val route = topology.routes.single()
        assertEquals("logical", route.openCameraId.value)
        assertEquals("member", route.physicalCameraId!!.value)
        assertTrue(route.capabilities.previewStreams.isEmpty())
    }

    @Test
    fun `java failure plus ndk success still produces topology`() {
        val topology = resolve(
            emptySnapshot(CameraRouteSource.JAVA_PUBLIC),
            snapshot(CameraRouteSource.NDK_ADVERTISED, evidence("ndk", source = CameraRouteSource.NDK_ADVERTISED)),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(CameraRouteSource.NDK_ADVERTISED, topology.routes.single().source)
    }

    @Test
    fun `ndk failure plus java success still produces topology`() {
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("java")),
            emptySnapshot(CameraRouteSource.NDK_ADVERTISED),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(CameraRouteSource.JAVA_PUBLIC, topology.routes.single().source)
    }

    @Test
    fun `both backends empty produce bounded empty topology`() {
        val topology = resolve(
            emptySnapshot(CameraRouteSource.JAVA_PUBLIC),
            emptySnapshot(CameraRouteSource.NDK_ADVERTISED),
        )
        assertTrue(topology.routes.isEmpty())
        assertTrue(topology.canonicalLenses.isEmpty())
        assertInvariants(topology)
    }

    @Test
    fun `duplicate evidence insertion does not change topology`() {
        val item = evidence("duplicate")
        val single = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, item))
        val duplicate = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, item, item, item))
        assertEquals(single, duplicate)
    }

    @Test
    fun `missing focal remains a valid separate route`() {
        val sparse = evidence("sparse").copy(focalLengthsMillimetres = emptyList())
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, sparse))
        assertEquals(1, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
    }

    @Test
    fun `missing sensor size remains a valid separate route`() {
        val sparse = evidence("sparse").copy(
            sensorPhysicalWidthMillimetres = null,
            sensorPhysicalHeightMillimetres = null,
        )
        assertEquals(1, resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, sparse)).routes.size)
    }

    @Test
    fun `conflicting facing providers on one exact transport remain one profile deterministically`() {
        val back = evidence("same", facing = LensFacing.BACK)
        val front = evidence("same", source = CameraRouteSource.NDK_ADVERTISED, facing = LensFacing.FRONT)
        val forward = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, back),
            snapshot(CameraRouteSource.NDK_ADVERTISED, front),
        )
        val reverse = resolve(
            snapshot(CameraRouteSource.NDK_ADVERTISED, front),
            snapshot(CameraRouteSource.JAVA_PUBLIC, back),
        )
        assertEquals(1, forward.routes.size)
        assertEquals(1, forward.canonicalLenses.size)
        assertEquals(1, forward.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(
            setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.NDK_ADVERTISED),
            forward.routes.single().sources,
        )
        assertEquals(LensFacing.UNKNOWN, forward.canonicalLenses.single().facing)
        assertEquals(forward, reverse)
        assertInvariants(forward)
    }

    @Test
    fun `small array variation on one exact transport remains one profile`() {
        val first = evidence("same", active = IntSize(4000, 3000))
        val second = evidence("same", source = CameraRouteSource.NDK_ADVERTISED, active = IntSize(3998, 2998))
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, first),
            snapshot(CameraRouteSource.NDK_ADVERTISED, second),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(1, topology.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(
            setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.NDK_ADVERTISED),
            topology.routes.single().sources,
        )
        assertEquals(
            setOf(IntSize(4000, 3000), IntSize(3998, 2998)),
            topology.evidence.mapNotNull { it.activeArray }.toSet(),
        )
        assertInvariants(topology)
    }

    @Test
    fun `same focal with different sensor remains separate`() {
        val first = evidence("one", focal = 4.2f, sensorWidth = 5.6f)
        val second = evidence("two", focal = 4.2f, sensorWidth = 6.4f)
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, first, second))
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun `opaque id text neither merges nor separates optical lenses`() {
        val strongAliases = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("camera-1"), evidence("camera-10")),
        )
        assertEquals(2, strongAliases.routes.size)
        assertEquals(2, strongAliases.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(1, strongAliases.canonicalLenses.size)

        val distinctOptics = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence("camera-1", focal = 4.2f),
                evidence("camera-10", focal = 8.0f),
            ),
        )
        assertEquals(2, distinctOptics.routes.size)
        assertEquals(2, distinctOptics.canonicalLenses.size)
        assertInvariants(strongAliases)
        assertInvariants(distinctOptics)
    }

    @Test
    fun `reordered snapshots evidence and backend delivery are identical`() {
        val a = evidence("a")
        val b = evidence("b", source = CameraRouteSource.NDK_ADVERTISED, focal = 8.0f)
        val forward = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, a),
            snapshot(CameraRouteSource.NDK_ADVERTISED, b),
        )
        val reverse = resolve(
            snapshot(CameraRouteSource.NDK_ADVERTISED, b),
            snapshot(CameraRouteSource.JAVA_PUBLIC, a),
        )
        val evidenceReverse = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("c"), evidence("d")),
        )
        val evidenceForward = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("d"), evidence("c")),
        )
        assertEquals(forward, reverse)
        assertEquals(evidenceForward, evidenceReverse)
    }

    @Test
    fun `compatible previous topology preserves route trust`() {
        val initial = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("trusted")))
        val trusted = withTrust(initial, CameraTrust.VERIFIED, PreviewTrust.VERIFIED, RawTrust.VERIFIED)
        val current = resolve(
            snapshot(CameraRouteSource.NDK_ADVERTISED, evidence("trusted", source = CameraRouteSource.NDK_ADVERTISED)),
            previous = trusted,
        )
        assertEquals(CameraTrust.VERIFIED, current.routes.single().metadataTrust)
        assertEquals(PreviewTrust.VERIFIED, current.routes.single().previewTrust)
        assertEquals(RawTrust.VERIFIED, current.routes.single().rawTrust)
        assertEquals(trusted.canonicalLenses.single().fingerprint, current.canonicalLenses.single().fingerprint)
    }

    @Test
    fun `stale previous topology cannot invent unsupported camera`() {
        val old = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("old")))
        val current = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("current")), previous = old)
        assertEquals(listOf("current"), current.routes.map { it.openCameraId.value })
    }

    @Test
    fun `minor live geometry change preserves strongly matching canonical lens`() {
        val related = physical("logical", "member", 4.2f)
        val direct = evidence("member", focal = 4.2f)
        val previous = resolve(
            snapshot(CameraRouteSource.JAVA_PHYSICAL, related),
            snapshot(CameraRouteSource.JAVA_PUBLIC, direct),
        )
        assertEquals(2, previous.routes.size)
        assertEquals(2, previous.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(1, previous.canonicalLenses.size)
        val oldFingerprint = previous.canonicalLenses.single().fingerprint

        val changedDirect = direct.copy(activeArray = IntSize(3990, 2990))
        val current = resolve(
            snapshot(CameraRouteSource.JAVA_PHYSICAL, related),
            snapshot(CameraRouteSource.JAVA_PUBLIC, changedDirect),
            previous = previous,
        )
        assertEquals(2, current.routes.size)
        assertEquals(2, current.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(1, current.canonicalLenses.size)
        assertEquals(oldFingerprint, current.canonicalLenses.single().fingerprint)
        assertInvariants(current)
    }

    @Test
    fun `unrelated similar route receives no preview trust`() {
        val old = withTrust(
            resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("old"))),
            CameraTrust.VERIFIED,
            PreviewTrust.VERIFIED,
            RawTrust.VERIFIED,
        )
        val current = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("new")), previous = old)
        assertEquals(PreviewTrust.UNKNOWN, current.routes.single().previewTrust)
        assertEquals(RawTrust.UNKNOWN, current.routes.single().rawTrust)
    }

    @Test
    fun `conflicting current metadata cannot inherit old route trust`() {
        val old = withTrust(
            resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("same", focal = 4.2f))),
            CameraTrust.VERIFIED,
            PreviewTrust.VERIFIED,
            RawTrust.VERIFIED,
        )
        val current = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("same", focal = 9.0f)),
            previous = old,
        )
        assertEquals(CameraTrust.ADVERTISED, current.routes.single().metadataTrust)
        assertEquals(PreviewTrust.UNKNOWN, current.routes.single().previewTrust)
        assertEquals(RawTrust.UNKNOWN, current.routes.single().rawTrust)
    }

    @Test
    fun `tiny provider float drift preserves optical alias while material sensor change separates`() {
        val related = physical("logical", "member", 4.2f).copy(sensorPhysicalWidthMillimetres = 5.60001f)
        val direct = evidence("member", focal = 4.2f, sensorWidth = 5.60002f)
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PHYSICAL, related),
            snapshot(CameraRouteSource.JAVA_PUBLIC, direct),
        )
        assertEquals(2, topology.routes.size)
        assertEquals(2, topology.canonicalLenses.flatMap { it.profiles }.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertInvariants(topology)

        val materiallyDifferent = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence("sensor-a", focal = 4.2f, sensorWidth = 5.6f),
                evidence("sensor-b", focal = 4.2f, sensorWidth = 6.4f),
            ),
        )
        assertEquals(2, materiallyDifferent.routes.size)
        assertEquals(2, materiallyDifferent.canonicalLenses.size)
        assertInvariants(materiallyDifferent)
    }

    @Test
    fun `global evidence bound rejects pathological input deterministically`() {
        val tooMany = (0..CameraTopologyResolver.MAX_TOTAL_EVIDENCE).map { index ->
            evidence("opaque-$index")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, *tooMany.toTypedArray()))
        }
    }

    @Test
    fun `merged capability bound rejects alias explosion`() {
        val firstStreams = (0 until 80).map { index ->
            CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(640 + index, 480), null)
        }
        val secondStreams = (0 until 80).map { index ->
            CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(900 + index, 480), null)
        }
        val first = evidence("same").copy(capabilities = CameraCapabilities(previewStreams = firstStreams))
        val second = evidence("same", source = CameraRouteSource.NDK_ADVERTISED).copy(
            capabilities = CameraCapabilities(previewStreams = secondStreams),
        )
        assertThrows(IllegalArgumentException::class.java) {
            resolve(
                snapshot(CameraRouteSource.JAVA_PUBLIC, first),
                snapshot(CameraRouteSource.NDK_ADVERTISED, second),
            )
        }
    }

    @Test
    fun `bounded permutation property keeps identical logical evidence topology`() {
        val base = listOf(
            evidence("a", focal = 2.0f),
            evidence("b", focal = 4.0f),
            evidence("c", focal = 6.0f),
            evidence("d", focal = 8.0f),
        )
        val expected = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, *base.toTypedArray()))
        permutations(base).forEach { permutation ->
            assertEquals(expected, resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, *permutation.toTypedArray())))
        }
    }

    @Test
    fun `bounded generated optional removal and compatible conflict matrix always satisfies invariants`() {
        val base = evidence("generated")
        val variants = listOf(
            base.copy(focalLengthsMillimetres = emptyList()),
            base.copy(sensorPhysicalWidthMillimetres = null, sensorPhysicalHeightMillimetres = null),
            base.copy(activeArray = null),
            base.copy(pixelArray = null),
            base.copy(sensorOrientationDegrees = null),
            base.copy(apertureValues = emptyList()),
            base.copy(colorFilterArrangement = null),
        )
        variants.forEach { variant ->
            assertInvariants(resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, variant)))
            val alias = variant.copy(source = CameraRouteSource.NDK_ADVERTISED)
            assertInvariants(
                resolve(
                    snapshot(CameraRouteSource.JAVA_PUBLIC, variant),
                    snapshot(CameraRouteSource.NDK_ADVERTISED, alias),
                ),
            )
        }
    }

    @Test
    fun `source permutations preserve exact alias topology`() {
        val sources = listOf(
            CameraRouteSource.JAVA_PUBLIC,
            CameraRouteSource.NDK_ADVERTISED,
            CameraRouteSource.NDK_DEEP,
        )
        val snapshots = sources.map { source -> snapshot(source, evidence("same", source = source)) }
        val expected = resolve(*snapshots.toTypedArray())
        permutations(snapshots).forEach { order -> assertEquals(expected, resolve(*order.toTypedArray())) }
        assertEquals(3, expected.routes.single().sources.size)
    }

    private fun assertIndependent(firstId: String, firstFocal: Float, secondId: String, secondFocal: Float) {
        val topology = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence(firstId, focal = firstFocal),
                evidence(secondId, focal = secondFocal),
            ),
        )
        assertEquals(2, topology.routes.size)
        assertEquals(2, topology.canonicalLenses.size)
        assertInvariants(topology)
    }

    private fun assertInvariants(topology: com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot) {
        assertTrue(topology.routes.size <= CameraTopologyResolver.MAX_ROUTES)
        assertTrue(topology.canonicalLenses.size <= CameraTopologyResolver.MAX_CANONICAL_LENSES)
        val profiles = topology.canonicalLenses.flatMap { it.profiles }
        assertTrue(profiles.size <= CameraTopologyResolver.MAX_PROFILES)
        assertEquals(topology.routes.size, topology.routes.map { it.id }.distinct().size)
        assertEquals(profiles.size, profiles.map { it.fingerprint }.distinct().size)
        assertEquals(topology.canonicalLenses.size, topology.canonicalLenses.map { it.fingerprint }.distinct().size)
        assertEquals(topology.routes.map { it.id }.toSet(), profiles.map { it.route.id }.toSet())
        assertTrue(topology.canonicalLenses.all { it.profiles.isNotEmpty() })
        assertTrue(topology.canonicalLenses.all { it.profiles.size <= CameraTopologyResolver.MAX_PROFILES_PER_LENS })
        assertTrue(topology.routes.all { it.sources.isNotEmpty() && it.sources.size <= CameraTopologyResolver.MAX_PROVENANCE_SOURCES })
    }

    private fun withTrust(
        topology: com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot,
        metadata: CameraTrust,
        preview: PreviewTrust,
        raw: RawTrust,
    ): com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot {
        val routes = topology.routes.map { it.copy(metadataTrust = metadata, previewTrust = preview, rawTrust = raw) }
        val routesById = routes.associateBy { it.id }
        return topology.copy(
            routes = routes,
            canonicalLenses = topology.canonicalLenses.map { lens ->
                lens.copy(profiles = lens.profiles.map { profile -> profile.copy(route = routesById.getValue(profile.route.id)) })
            },
        )
    }

    private fun resolve(
        vararg snapshots: CameraEvidenceSnapshot,
        previous: com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot? = null,
    ) = CameraTopologyResolver.resolve(
        environment = environment,
        snapshots = snapshots.toList(),
        generatedAtElapsedRealtimeNs = 100L,
        previousTrustedTopology = previous,
    )

    private fun emptySnapshot(source: CameraRouteSource) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = emptyList(),
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun snapshot(source: CameraRouteSource, vararg values: CameraMetadataEvidence) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = values.map { it.copy(source = source) },
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun physical(parent: String, member: String, focal: Float) = evidence(
        id = parent,
        source = CameraRouteSource.JAVA_PHYSICAL,
        focal = focal,
    ).copy(
        physicalId = PhysicalCameraId(member),
        logicalParentId = CameraTransportId(parent),
    )

    private fun evidence(
        id: String,
        source: CameraRouteSource = CameraRouteSource.JAVA_PUBLIC,
        facing: LensFacing = LensFacing.BACK,
        focal: Float = 4.2f,
        sensorWidth: Float = 5.6f,
        active: IntSize = IntSize(4000, 3000),
    ) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        facing = facing,
        focalLengthsMillimetres = listOf(focal),
        sensorPhysicalWidthMillimetres = sensorWidth,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = active,
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        apertureValues = listOf(1.8f),
        colorFilterArrangement = 0,
        capabilities = CameraCapabilities(),
    )

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.size <= 1) return listOf(values)
        val result = ArrayList<List<T>>()
        values.forEachIndexed { index, value ->
            val rest = values.toMutableList().also { it.removeAt(index) }
            permutations(rest).forEach { tail -> result += listOf(value) + tail }
        }
        return result
    }
}
