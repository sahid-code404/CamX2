package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.io.ByteArrayOutputStream
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdkAdvertisedCameraEvidenceBackendTest {
    private val environment = CameraEnvironmentFingerprint("camx-107-ndk-test")

    @Test
    fun `api 23 reports unavailable without native call`() = runSuspend {
        var called = false
        val backend = NdkAdvertisedCameraEvidenceBackend(
            environment = environment,
            deviceApi = { 23 },
            clockNanos = { 11L },
            rawCollector = {
                called = true
                error("must not run")
            },
        )
        val report = backend.discoverReport(DiscoveryDepth.ADVERTISED)
        assertEquals(NdkAdvertisedRuntimeState.UNAVAILABLE, report.runtimeState)
        assertFalse(called)
        assertTrue(report.snapshot.evidence.isEmpty())
    }

    @Test
    fun `startup seed never calls ndk backend`() = runSuspend {
        var called = false
        val backend = backend {
            called = true
            availablePayload()
        }
        val report = backend.discoverReport(DiscoveryDepth.STARTUP_SEED)
        assertEquals(NdkAdvertisedRuntimeState.NOT_RUN, report.runtimeState)
        assertFalse(called)
    }

    @Test
    fun `empty advertised list decodes`() {
        val decoded = NdkAdvertisedSnapshotCodec.decode(availablePayload())
        assertNotNull(decoded)
        assertTrue(decoded!!.runtimeAvailable)
        assertTrue(decoded.evidence.isEmpty())
    }

    @Test
    fun `one advertised camera retains metadata`() {
        val decoded = NdkAdvertisedSnapshotCodec.decode(
            availablePayload(records = listOf(fullRecord("opaque-rear"))),
        )!!
        val item = decoded.evidence.single()
        assertEquals("opaque-rear", item.transportId.value)
        assertEquals(LensFacing.BACK, item.facing)
        assertEquals(listOf(4.2f), item.focalLengthsMillimetres)
        assertEquals(4000, item.activeArray!!.width)
        assertEquals(1, item.capabilities.previewStreams.size)
        assertEquals(1, item.capabilities.fpsRanges.size)
        assertEquals(1, item.capabilities.rawSizes.size)
    }

    @Test
    fun `multiple cameras decode independent of payload order`() {
        val first = NdkAdvertisedSnapshotCodec.decode(
            availablePayload(records = listOf(fullRecord("beta"), fullRecord("alpha"))),
        )!!
        val second = NdkAdvertisedSnapshotCodec.decode(
            availablePayload(records = listOf(fullRecord("alpha"), fullRecord("beta"))),
        )!!
        assertEquals(first.evidence, second.evidence)
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertNull(
            NdkAdvertisedSnapshotCodec.decode(
                availablePayload(records = listOf(fullRecord("same"), fullRecord("same"))),
            ),
        )
    }

    @Test
    fun `malformed id and count are rejected`() {
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(recordCountOverride = 65)))
        assertNull(
            NdkAdvertisedSnapshotCodec.decode(
                availablePayload(records = listOf(fullRecord("x".repeat(NDK_AUX_MAX_ID_BYTES + 1)))),
            ),
        )
    }

    @Test
    fun `missing optional tags remain valid evidence`() {
        val decoded = NdkAdvertisedSnapshotCodec.decode(
            availablePayload(records = listOf(RecordFixture(id = "sparse"))),
        )!!
        val item = decoded.evidence.single()
        assertEquals(LensFacing.UNKNOWN, item.facing)
        assertTrue(item.focalLengthsMillimetres.isEmpty())
        assertNull(item.sensorPhysicalWidthMillimetres)
        assertTrue(item.capabilities.previewStreams.isEmpty())
    }

    @Test
    fun `focal length bound is enforced`() {
        val record = fullRecord("focal").copy(focals = List(NDK_AUX_MAX_FOCAL_LENGTHS + 1) { 1f })
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(records = listOf(record))))
    }

    @Test
    fun `aperture bound is enforced`() {
        val record = fullRecord("aperture").copy(apertures = List(NDK_AUX_MAX_APERTURES + 1) { 1.8f })
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(records = listOf(record))))
    }

    @Test
    fun `preview stream bound is enforced`() {
        val record = fullRecord("preview").copy(
            previews = List(NDK_AUX_MAX_PREVIEW_STREAMS + 1) { Triple(640, 480, -1L) },
        )
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(records = listOf(record))))
    }

    @Test
    fun `fps range bound is enforced`() {
        val record = fullRecord("fps").copy(
            fps = List(NDK_AUX_MAX_FPS_RANGES + 1) { 15 to 30 },
        )
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(records = listOf(record))))
    }

    @Test
    fun `raw size bound is enforced`() {
        val record = fullRecord("raw").copy(
            raw = List(NDK_AUX_MAX_RAW_SIZES + 1) { 4000 to 3000 },
        )
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(records = listOf(record))))
    }

    @Test
    fun `malformed metadata entry is rejected`() {
        val record = fullRecord("bad-orientation").copy(orientation = 45)
        assertNull(NdkAdvertisedSnapshotCodec.decode(availablePayload(records = listOf(record))))
    }

    @Test
    fun `per id metadata failure is retained`() {
        val decoded = NdkAdvertisedSnapshotCodec.decode(
            availablePayload(failures = listOf(4 to "broken")),
        )!!
        assertEquals(1, decoded.failures.size)
        assertEquals(NdkAdvertisedEvidenceFailureKind.METADATA_UNAVAILABLE, decoded.failures.single().kind)
        assertEquals("broken", decoded.failures.single().transportId)
    }

    @Test
    fun `one failed camera does not erase another`() = runSuspend {
        val bytes = availablePayload(
            records = listOf(fullRecord("valid")),
            failures = listOf(4 to "broken"),
        )
        val report = backend { bytes }.discoverReport(DiscoveryDepth.ADVERTISED)
        assertEquals(NdkAdvertisedRuntimeState.AVAILABLE, report.runtimeState)
        assertEquals(listOf("valid"), report.snapshot.evidence.map { it.transportId.value })
        assertEquals("broken", report.failures.single().transportId)
    }

    @Test
    fun `unavailable native status cannot carry records`() {
        val writer = Writer()
        writer.raw(byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte()))
        writer.u16(1)
        writer.u8(1)
        writer.u8(0)
        writer.u16(1)
        writer.u16(0)
        assertNull(NdkAdvertisedSnapshotCodec.decode(writer.bytes()))
    }

    @Test
    fun `repeated decode is identical and bounded`() {
        val bytes = availablePayload(records = listOf(fullRecord("repeat")))
        val first = NdkAdvertisedSnapshotCodec.decode(bytes)!!
        val second = NdkAdvertisedSnapshotCodec.decode(bytes)!!
        assertEquals(first, second)
        assertTrue(bytes.size <= NDK_AUX_MAX_ENCODED_BYTES)
    }

    private fun backend(rawCollector: (Int) -> ByteArray?) = NdkAdvertisedCameraEvidenceBackend(
        environment = environment,
        deviceApi = { 24 },
        clockNanos = { 123L },
        rawCollector = rawCollector,
    )

    private fun fullRecord(id: String) = RecordFixture(
        id = id,
        facingCode = 2,
        focals = listOf(4.2f),
        sensorSize = 5.6f to 4.2f,
        active = 4000 to 3000,
        pixel = 4032 to 3024,
        orientation = 90,
        apertures = listOf(1.8f),
        cfa = 0,
        previews = listOf(Triple(1920, 1080, 33_333_333L)),
        fps = listOf(15 to 30),
        raw = listOf(4000 to 3000),
    )

    private fun availablePayload(
        records: List<RecordFixture> = emptyList(),
        failures: List<Pair<Int, String>> = emptyList(),
        recordCountOverride: Int? = null,
    ): ByteArray {
        val writer = Writer()
        writer.raw(byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte()))
        writer.u16(1)
        writer.u8(0)
        writer.u8(0)
        writer.u16(recordCountOverride ?: records.size)
        writer.u16(failures.size)
        records.forEach { writer.record(it) }
        failures.forEach { (kind, id) ->
            writer.u8(kind)
            writer.string(id)
        }
        return writer.bytes()
    }

    private data class RecordFixture(
        val id: String,
        val facingCode: Int = 0,
        val focals: List<Float> = emptyList(),
        val sensorSize: Pair<Float, Float>? = null,
        val active: Pair<Int, Int>? = null,
        val pixel: Pair<Int, Int>? = null,
        val orientation: Int? = null,
        val apertures: List<Float> = emptyList(),
        val cfa: Int? = null,
        val previews: List<Triple<Int, Int, Long>> = emptyList(),
        val fps: List<Pair<Int, Int>> = emptyList(),
        val raw: List<Pair<Int, Int>> = emptyList(),
    )

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun bytes(): ByteArray = output.toByteArray()
        fun raw(value: ByteArray) { output.write(value) }
        fun u8(value: Int) { output.write(value and 0xff) }
        fun u16(value: Int) {
            u8(value)
            u8(value ushr 8)
        }
        fun i32(value: Int) {
            repeat(4) { offset -> u8(value ushr (offset * 8)) }
        }
        fun i64(value: Long) {
            repeat(8) { offset -> u8((value ushr (offset * 8)).toInt()) }
        }
        fun f32(value: Float) { i32(value.toRawBits()) }
        fun string(value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            u16(raw.size)
            raw(raw)
        }
        fun record(record: RecordFixture) {
            string(record.id)
            u8(record.facingCode)
            var flags = 0
            if (record.sensorSize != null) flags = flags or 1
            if (record.active != null) flags = flags or 2
            if (record.pixel != null) flags = flags or 4
            if (record.orientation != null) flags = flags or 8
            if (record.cfa != null) flags = flags or 16
            u8(flags)
            u16(record.focals.size)
            u16(record.apertures.size)
            u16(record.previews.size)
            u16(record.fps.size)
            u16(record.raw.size)
            record.sensorSize?.let { (width, height) -> f32(width); f32(height) }
            record.active?.let { (width, height) -> i32(width); i32(height) }
            record.pixel?.let { (width, height) -> i32(width); i32(height) }
            record.orientation?.let(::i32)
            record.cfa?.let(::i32)
            record.focals.forEach(::f32)
            record.apertures.forEach(::f32)
            record.previews.forEach { (width, height, duration) ->
                i32(width); i32(height); i64(duration)
            }
            record.fps.forEach { (minimum, maximum) -> i32(minimum); i32(maximum) }
            record.raw.forEach { (width, height) -> i32(width); i32(height) }
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(outcome: Result<T>) { result = outcome }
        })
        return checkNotNull(result) { "Test coroutine did not complete synchronously" }.getOrThrow()
    }
}
