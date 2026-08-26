package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalFirstInstallSeedDiscoveryTest {
    private val environment = CameraEnvironmentFingerprint("environment:seed-test")

    @Test
    fun emptyAdvertisedIdListReturnsRecoverableEmptyResult() {
        val source = FakeSeedSource(emptyList())
        val result = discover(source)

        assertNull(result.route)
        assertTrue(result.evidenceSnapshot.evidence.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertEquals(0, result.examinedIdCount)
    }

    @Test
    fun oneValidRearRouteIsSelectedWithAdvertisedTrustAndProvenance() {
        val source = FakeSeedSource(listOf("opaque-rear")).withEvidence(
            seed("opaque-rear", facing = LensFacing.BACK),
        )
        val result = discover(source)

        assertEquals(CameraTransportId("opaque-rear"), result.route?.openCameraId)
        assertEquals(CameraTrust.ADVERTISED, result.route?.metadataTrust)
        assertEquals(CameraRouteSource.JAVA_PUBLIC, result.route?.source)
        assertEquals(setOf(CameraRouteSource.JAVA_PUBLIC), result.route?.sources)
        assertEquals(CameraCapabilities(), result.route?.capabilities)
    }

    @Test
    fun oneValidFrontRouteIsSelected() {
        val source = FakeSeedSource(listOf("opaque-front")).withEvidence(
            seed("opaque-front", facing = LensFacing.FRONT),
        )
        assertEquals(CameraTransportId("opaque-front"), discover(source).route?.openCameraId)
    }

    @Test
    fun mixedFrontAndRearCandidatesPreferRearFacingEvidence() {
        val source = FakeSeedSource(listOf("front-route", "rear-route"))
            .withEvidence(seed("front-route", facing = LensFacing.FRONT))
            .withEvidence(seed("rear-route", facing = LensFacing.BACK, backwardCompatible = null))

        assertEquals(CameraTransportId("rear-route"), discover(source).route?.openCameraId)
    }

    @Test
    fun inaccessibleAdvertisedIdIsIsolated() {
        val source = FakeSeedSource(listOf("broken")).withFailure("broken")
        val result = discover(source)

        assertNull(result.route)
        assertTrue(result.evidenceSnapshot.evidence.isEmpty())
        assertEquals(
            listOf(SeedDiscoveryFailureKind.CHARACTERISTICS_UNAVAILABLE),
            result.failures.map(SeedDiscoveryFailure::kind),
        )
    }

    @Test
    fun oneBrokenIdDoesNotEraseValidRoute() {
        val source = FakeSeedSource(listOf("broken", "valid"))
            .withFailure("broken")
            .withEvidence(seed("valid", facing = LensFacing.BACK))
        val result = discover(source)

        assertEquals(CameraTransportId("valid"), result.route?.openCameraId)
        assertEquals(1, result.evidenceSnapshot.evidence.size)
        assertEquals(1, result.failures.size)
    }

    @Test
    fun allAdvertisedIdsInaccessibleReturnsRecoverableEmptyResult() {
        val source = FakeSeedSource(listOf("broken-a", "broken-b"))
            .withFailure("broken-a")
            .withFailure("broken-b")
        val result = discover(source)

        assertNull(result.route)
        assertEquals(2, result.failures.size)
        assertEquals(2, result.examinedIdCount)
    }

    @Test
    fun missingFacingDoesNotInvalidateOtherwiseCredibleRoute() {
        val source = FakeSeedSource(listOf("unknown-facing")).withEvidence(
            seed("unknown-facing", facing = LensFacing.UNKNOWN),
        )
        assertEquals(CameraTransportId("unknown-facing"), discover(source).route?.openCameraId)
    }

    @Test
    fun missingFocalAndPhysicalOpticsDoesNotInvalidatePreviewCapableRoute() {
        val source = FakeSeedSource(listOf("no-optics")).withEvidence(
            seed(
                "no-optics",
                focalLengths = emptyList(),
                sensorWidth = null,
                sensorHeight = null,
            ),
        )
        assertEquals(CameraTransportId("no-optics"), discover(source).route?.openCameraId)
    }

    @Test
    fun incompleteOptionalMetadataRemainsCredible() {
        val source = FakeSeedSource(listOf("partial")).withEvidence(
            seed(
                "partial",
                facing = LensFacing.BACK,
                focalLengths = listOf(4.2f),
                sensorWidth = null,
                sensorHeight = null,
                backwardCompatible = null,
            ),
        )
        assertEquals(CameraTransportId("partial"), discover(source).route?.openCameraId)
    }

    @Test
    fun duplicateAndReorderedAdvertisedIdsReadEachUniqueRouteOnce() {
        val first = FakeSeedSource(listOf("b", "a", "b", "a"))
            .withEvidence(seed("a", facing = LensFacing.FRONT))
            .withEvidence(seed("b", facing = LensFacing.BACK))
        val second = FakeSeedSource(listOf("a", "b", "a", "b"))
            .withEvidence(seed("a", facing = LensFacing.FRONT))
            .withEvidence(seed("b", facing = LensFacing.BACK))

        val firstResult = discover(first)
        val secondResult = discover(second)

        assertEquals(firstResult.route, secondResult.route)
        assertEquals(first.reads.toSet(), second.reads.toSet())
        assertEquals(2, first.reads.size)
        assertEquals(2, second.reads.size)
    }

    @Test
    fun deterministicTieBreakIsIndependentOfEnumerationOrder() {
        val first = FakeSeedSource(listOf("opaque-a", "opaque-b"))
            .withEvidence(seed("opaque-a", facing = LensFacing.BACK))
            .withEvidence(seed("opaque-b", facing = LensFacing.BACK))
        val second = FakeSeedSource(listOf("opaque-b", "opaque-a"))
            .withEvidence(seed("opaque-a", facing = LensFacing.BACK))
            .withEvidence(seed("opaque-b", facing = LensFacing.BACK))

        assertEquals(discover(first).route, discover(second).route)
    }

    @Test
    fun numericCameraIdsNeverAssignCameraRoles() {
        val source = FakeSeedSource(listOf("0", "9"))
            .withEvidence(seed("0", facing = LensFacing.FRONT))
            .withEvidence(seed("9", facing = LensFacing.BACK))

        assertEquals(CameraTransportId("9"), discover(source).route?.openCameraId)
    }

    @Test
    fun cancellationStopsTheLocalBatchBeforeLaterCharacteristicsReads() {
        val job = Job()
        val source = FakeSeedSource(listOf("a", "b", "c"))
            .withEvidence(seed("a"))
            .withEvidence(seed("b"))
            .withEvidence(seed("c"))
        source.onRead = {
            if (source.reads.size == 1) job.cancel()
        }

        val outcome = awaitSuspendResult(job) { discovery(source).discover() }

        assertTrue(outcome.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEquals(1, source.reads.size)
    }

    @Test
    fun oversizedAdvertisedBatchFailsClosedBeforeCharacteristicsReads() {
        val ids = (0..SEED_MAX_ADVERTISED_IDS).map { "opaque-$it" }
        val source = FakeSeedSource(ids)
        val result = discover(source)

        assertNull(result.route)
        assertTrue(result.batchLimitExceeded)
        assertTrue(source.reads.isEmpty())
        assertEquals(
            listOf(SeedDiscoveryFailureKind.ADVERTISED_ID_LIMIT_EXCEEDED),
            result.failures.map(SeedDiscoveryFailure::kind),
        )
    }

    @Test
    fun failedRouteNeverRemovesOtherSuccessfulEvidence() {
        val source = FakeSeedSource(listOf("valid-front", "broken", "valid-rear"))
            .withEvidence(seed("valid-front", facing = LensFacing.FRONT))
            .withFailure("broken")
            .withEvidence(seed("valid-rear", facing = LensFacing.BACK))
        val result = discover(source)

        assertEquals(2, result.evidenceSnapshot.evidence.size)
        assertEquals(CameraTransportId("valid-rear"), result.route?.openCameraId)
    }

    @Test
    fun zeroPreviewCredibleCandidatesReturnsRecoverableEmptyResult() {
        val source = FakeSeedSource(listOf("metadata-only")).withEvidence(
            seed("metadata-only", privatePreview = false),
        )
        val result = discover(source)

        assertNull(result.route)
        assertEquals(1, result.evidenceSnapshot.evidence.size)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun sameFacingCandidatesPreferAdvertisedBackwardCompatibilityThenOpticalCompleteness() {
        val source = FakeSeedSource(listOf("partial", "compatible", "incompatible"))
            .withEvidence(
                seed(
                    "partial",
                    facing = LensFacing.BACK,
                    focalLengths = emptyList(),
                    sensorWidth = null,
                    sensorHeight = null,
                    backwardCompatible = true,
                ),
            )
            .withEvidence(
                seed(
                    "compatible",
                    facing = LensFacing.BACK,
                    focalLengths = listOf(4.2f),
                    sensorWidth = 5.6f,
                    sensorHeight = 4.2f,
                    backwardCompatible = true,
                ),
            )
            .withEvidence(
                seed(
                    "incompatible",
                    facing = LensFacing.BACK,
                    focalLengths = listOf(4.2f),
                    sensorWidth = 5.6f,
                    sensorHeight = 4.2f,
                    backwardCompatible = false,
                ),
            )

        assertEquals(CameraTransportId("compatible"), discover(source).route?.openCameraId)
    }

    @Test
    fun enumerationFailureIsRecoverableAndPerformsNoCharacteristicsReads() {
        val source = FakeSeedSource(emptyList()).apply { enumerationFailure = true }
        val result = discover(source)

        assertNull(result.route)
        assertTrue(source.reads.isEmpty())
        assertEquals(SeedDiscoveryFailureKind.ID_ENUMERATION_UNAVAILABLE, result.failures.single().kind)
    }

    private fun discover(source: FakeSeedSource): SeedDiscoveryResult =
        awaitSuspendResult { discovery(source).discover() }.getOrThrow()

    private fun discovery(source: FakeSeedSource) = MinimalFirstInstallSeedDiscovery(
        source = source,
        environment = environment,
        elapsedRealtimeNs = { 123L },
    )

    private fun seed(
        id: String,
        facing: LensFacing = LensFacing.BACK,
        focalLengths: List<Float> = listOf(4.2f),
        sensorWidth: Float? = 5.6f,
        sensorHeight: Float? = 4.2f,
        privatePreview: Boolean = true,
        backwardCompatible: Boolean? = true,
    ) = SeedCameraEvidence(
        metadata = CameraMetadataEvidence(
            source = CameraRouteSource.JAVA_PUBLIC,
            transportId = CameraTransportId(id),
            facing = facing,
            focalLengthsMillimetres = focalLengths,
            sensorPhysicalWidthMillimetres = sensorWidth,
            sensorPhysicalHeightMillimetres = sensorHeight,
            capabilities = CameraCapabilities(),
        ),
        privatePreviewOutputAdvertised = privatePreview,
        backwardCompatibleAdvertised = backwardCompatible,
    )

    private fun <T> awaitSuspendResult(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> T,
    ): Result<T> {
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = context

                override fun resumeWith(result: Result<T>) {
                    outcome.set(result)
                    completed.countDown()
                }
            },
        )
        check(completed.await(5, TimeUnit.SECONDS))
        return checkNotNull(outcome.get())
    }
}

private class FakeSeedSource(
    private val advertised: List<String>,
) : PublicCameraSeedMetadataSource {
    private val evidence = LinkedHashMap<CameraTransportId, SeedCameraEvidence>()
    private val failures = LinkedHashSet<CameraTransportId>()
    val reads = mutableListOf<CameraTransportId>()
    var enumerationFailure: Boolean = false
    var onRead: (CameraTransportId) -> Unit = {}

    fun withEvidence(value: SeedCameraEvidence): FakeSeedSource = apply {
        evidence[value.metadata.transportId] = value
    }

    fun withFailure(id: String): FakeSeedSource = apply {
        failures += CameraTransportId(id)
    }

    override fun advertisedCameraIds(): List<String> {
        if (enumerationFailure) throw IllegalStateException("enumeration unavailable")
        return advertised
    }

    override fun readSeedEvidence(transportId: CameraTransportId): SeedCameraEvidence {
        reads += transportId
        onRead(transportId)
        if (transportId in failures) throw IllegalStateException("characteristics unavailable")
        return evidence[transportId] ?: throw IllegalStateException("missing fake evidence")
    }
}
