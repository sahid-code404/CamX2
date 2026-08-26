package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheCodecTest {
    @Test
    fun hotRoundTripAndDeterministicEncoding() {
        val snapshot = hotSnapshot()
        val first = HotStartCacheCodec.encode(snapshot)
        val second = HotStartCacheCodec.encode(snapshot)
        assertArrayEquals(first, second)
        assertEquals(CacheRead.Hit(snapshot), HotStartCacheCodec.decode(first, TEST_ENVIRONMENT))
        assertTrue(first.size <= CacheBounds.HOT_FILE_BYTES)
    }

    @Test
    fun hotEnvironmentMismatchIsSafeMiss() {
        val encoded = HotStartCacheCodec.encode(hotSnapshot())
        assertEquals(
            CacheRead.Miss,
            HotStartCacheCodec.decode(encoded, CameraEnvironmentFingerprint("environment:other")),
        )
    }

    @Test
    fun hotRejectsBadMagicUnknownSchemaChecksumTruncationAndTrailingData() {
        val encoded = HotStartCacheCodec.encode(hotSnapshot())
        assertTrue(HotStartCacheCodec.decode(withInt(encoded, 0, 0), TEST_ENVIRONMENT) is CacheRead.Corrupt)
        assertEquals(CacheRead.Miss, HotStartCacheCodec.decode(withInt(encoded, 8, 99), TEST_ENVIRONMENT))
        val checksum = encoded.copyOf().also {
            it[CacheBounds.ENVELOPE_BYTES] = (it[CacheBounds.ENVELOPE_BYTES].toInt() xor 1).toByte()
        }
        assertTrue(HotStartCacheCodec.decode(checksum, TEST_ENVIRONMENT) is CacheRead.Corrupt)
        for (cut in listOf(1, CacheBounds.ENVELOPE_BYTES - 1, encoded.size - 1)) {
            assertTrue(HotStartCacheCodec.decode(encoded.copyOf(cut), TEST_ENVIRONMENT) is CacheRead.Corrupt)
        }
        assertTrue(
            HotStartCacheCodec.decode(encoded + byteArrayOf(1), TEST_ENVIRONMENT) is CacheRead.Corrupt,
        )
    }

    @Test
    fun hotRejectsOversizedStringInvalidUtf8BooleanAndEnum() {
        val encoded = HotStartCacheCodec.encode(hotSnapshot())
        val oversized = encoded.copyOf()
        putInt(oversized, CacheBounds.ENVELOPE_BYTES, CacheBounds.ENVIRONMENT_BYTES + 1)
        rewriteCrc(oversized)
        assertTrue(HotStartCacheCodec.decode(oversized, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val invalidUtf8 = encoded.copyOf()
        val environmentLength = intAt(invalidUtf8, CacheBounds.ENVELOPE_BYTES)
        assertTrue(environmentLength >= 2)
        invalidUtf8[CacheBounds.ENVELOPE_BYTES + 4] = 0xC3.toByte()
        invalidUtf8[CacheBounds.ENVELOPE_BYTES + 5] = 0x28
        rewriteCrc(invalidUtf8)
        assertTrue(HotStartCacheCodec.decode(invalidUtf8, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val invalidBoolean = encoded.copyOf()
        val physicalPresenceOffset = hotPhysicalPresenceOffset(invalidBoolean)
        invalidBoolean[physicalPresenceOffset] = 2
        rewriteCrc(invalidBoolean)
        assertTrue(HotStartCacheCodec.decode(invalidBoolean, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val invalidEnum = encoded.copyOf()
        val streamTypeOffset = hotPreviewStreamOffset(invalidEnum)
        putInt(invalidEnum, streamTypeOffset, 999)
        rewriteCrc(invalidEnum)
        assertTrue(HotStartCacheCodec.decode(invalidEnum, TEST_ENVIRONMENT) is CacheRead.Corrupt)
    }

    @Test
    fun emptyTopologyRoundTripIsIndependentAndDeterministic() {
        val snapshot = emptyTopology()
        val first = TopologyCacheCodec.encode(snapshot)
        val second = TopologyCacheCodec.encode(snapshot)
        assertArrayEquals(first, second)
        val decoded = TopologyCacheCodec.decode(first, TEST_ENVIRONMENT)
        assertTrue(decoded is CacheRead.Hit)
        assertEquals(snapshot, (decoded as CacheRead.Hit).value)
    }

    @Test
    fun representativeTopologyRoundTripPreservesTypedGraph() {
        val snapshot = representativeTopology()
        val encoded = TopologyCacheCodec.encode(snapshot)
        val decoded = TopologyCacheCodec.decode(encoded, TEST_ENVIRONMENT)
        assertTrue(decoded is CacheRead.Hit)
        assertEquals(snapshot, (decoded as CacheRead.Hit).value)
        assertArrayEquals(encoded, TopologyCacheCodec.encode(snapshot))
        assertTrue(encoded.size <= CacheBounds.TOPOLOGY_FILE_BYTES)
    }

    @Test
    fun topologyEnvironmentMismatchChecksumTruncationAndUnknownSchemaFailClosed() {
        val encoded = TopologyCacheCodec.encode(representativeTopology())
        assertEquals(
            CacheRead.Miss,
            TopologyCacheCodec.decode(encoded, CameraEnvironmentFingerprint("environment:other")),
        )
        val checksum = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(TopologyCacheCodec.decode(checksum, TEST_ENVIRONMENT) is CacheRead.Corrupt)
        assertTrue(
            TopologyCacheCodec.decode(encoded.copyOf(encoded.size - 3), TEST_ENVIRONMENT) is CacheRead.Corrupt,
        )
        assertEquals(CacheRead.Miss, TopologyCacheCodec.decode(withInt(encoded, 8, 99), TEST_ENVIRONMENT))
    }

    @Test
    fun parity4RejectsLegacyTopologySchemaWithoutInvalidatingIndependentCaches() {
        assertEquals(2, CameraSchemaVersions.TOPOLOGY)
        assertEquals(1, CameraSchemaVersions.HOT_START)
        assertEquals(1, CameraSchemaVersions.DEEP_DISCOVERY)
        assertEquals(1, CameraSchemaVersions.LENS_REFERENCE)

        val current = TopologyCacheCodec.encode(representativeTopology())
        val legacy = withInt(current, 8, 1)
        assertEquals(CacheRead.Miss, TopologyCacheCodec.decode(legacy, TEST_ENVIRONMENT))

        val inspection = TopologyCacheMigrationInspector.inspectBytes(legacy, TEST_ENVIRONMENT)
        assertEquals(TopologyCacheInspectionStatus.INCOMPATIBLE_SCHEMA, inspection.status)
        assertEquals(1, inspection.storedSchema)
        assertEquals(true, inspection.environmentCompatible)

        val currentInspection = TopologyCacheMigrationInspector.inspectBytes(current, TEST_ENVIRONMENT)
        assertEquals(TopologyCacheInspectionStatus.COMPATIBLE, currentInspection.status)
        assertEquals(CameraSchemaVersions.TOPOLOGY, currentInspection.storedSchema)
        assertEquals(true, currentInspection.environmentCompatible)
    }

    @Test
    fun topologyRejectsExcessiveRouteLensProfileAndEvidenceCounts() {
        val empty = TopologyCacheCodec.encode(emptyTopology())
        val routeCountOffset = emptyTopologyRouteCountOffset(empty)
        val excessiveRoutes = withIntAndCrc(empty, routeCountOffset, CacheBounds.ROUTES + 1)
        assertTrue(TopologyCacheCodec.decode(excessiveRoutes, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val lensCountOffset = routeCountOffset + 4
        val excessiveLenses = withIntAndCrc(empty, lensCountOffset, CacheBounds.CANONICAL_LENSES + 1)
        assertTrue(TopologyCacheCodec.decode(excessiveLenses, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val evidenceCountOffset = lensCountOffset + 4
        val excessiveEvidence = withIntAndCrc(empty, evidenceCountOffset, CacheBounds.EVIDENCE + 1)
        assertTrue(TopologyCacheCodec.decode(excessiveEvidence, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val one = TopologyCacheCodec.encode(representativeTopology())
        val profileCountOffset = firstProfileCountOffset(one, "lens:back")
        val excessiveProfiles = withIntAndCrc(one, profileCountOffset, CacheBounds.PROFILES_PER_LENS + 1)
        assertTrue(TopologyCacheCodec.decode(excessiveProfiles, TEST_ENVIRONMENT) is CacheRead.Corrupt)
    }

    @Test
    fun topologyRejectsExcessiveNestedCapabilityCountMalformedEnumAndRelationship() {
        val encoded = TopologyCacheCodec.encode(representativeTopology())
        val streamCountOffset = firstRoutePreviewStreamCountOffset(encoded)
        val excessiveStreams = withIntAndCrc(encoded, streamCountOffset, CacheBounds.PREVIEW_STREAMS + 1)
        assertTrue(TopologyCacheCodec.decode(excessiveStreams, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val sourceOffset = firstRouteSourceOffset(encoded)
        val invalidEnum = withIntAndCrc(encoded, sourceOffset, 999)
        assertTrue(TopologyCacheCodec.decode(invalidEnum, TEST_ENVIRONMENT) is CacheRead.Corrupt)

        val relationship = encoded.copyOf()
        val needle = "route:back".toByteArray(StandardCharsets.UTF_8)
        val occurrences = findOccurrences(relationship, needle)
        assertTrue(occurrences.size >= 2)
        val profileRoute = occurrences.last()
        val replacement = "route:xxxx".toByteArray(StandardCharsets.UTF_8)
        assertEquals(needle.size, replacement.size)
        replacement.copyInto(relationship, profileRoute)
        rewriteCrc(relationship)
        assertTrue(TopologyCacheCodec.decode(relationship, TEST_ENVIRONMENT) is CacheRead.Corrupt)
    }

    private fun emptyTopologyRouteCountOffset(bytes: ByteArray): Int {
        val payload = CacheBounds.ENVELOPE_BYTES
        val environmentLength = intAt(bytes, payload)
        return payload + 4 + environmentLength + 8
    }

    private fun firstRouteSourceOffset(bytes: ByteArray): Int {
        var offset = emptyTopologyRouteCountOffset(bytes) + 4
        val routeLength = intAt(bytes, offset)
        offset += 4 + routeLength
        return offset
    }

    private fun firstRoutePreviewStreamCountOffset(bytes: ByteArray): Int {
        var offset = firstRouteSourceOffset(bytes) + 4
        val openLength = intAt(bytes, offset)
        offset += 4 + openLength
        val physicalPresent = bytes[offset++].toInt() and 0xff
        if (physicalPresent == 1) {
            val physicalLength = intAt(bytes, offset)
            offset += 4 + physicalLength
        }
        return offset
    }

    private fun firstProfileCountOffset(bytes: ByteArray, lensFingerprint: String): Int {
        val needle = lensFingerprint.toByteArray(StandardCharsets.UTF_8)
        val start = findOccurrences(bytes, needle).first()
        return start + needle.size + 4
    }

    private fun hotPhysicalPresenceOffset(bytes: ByteArray): Int {
        var offset = CacheBounds.ENVELOPE_BYTES
        repeat(5) {
            val length = intAt(bytes, offset)
            offset += 4 + length
        }
        return offset
    }

    private fun hotPreviewStreamOffset(bytes: ByteArray): Int {
        var offset = hotPhysicalPresenceOffset(bytes)
        val present = bytes[offset++].toInt() and 0xff
        if (present == 1) {
            val length = intAt(bytes, offset)
            offset += 4 + length
        }
        return offset
    }

    private fun findOccurrences(bytes: ByteArray, needle: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        for (index in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[index + it] == needle[it] }) result += index
        }
        return result
    }

    private fun withInt(bytes: ByteArray, offset: Int, value: Int): ByteArray =
        bytes.copyOf().also { putInt(it, offset, value) }

    private fun withIntAndCrc(bytes: ByteArray, offset: Int, value: Int): ByteArray =
        bytes.copyOf().also {
            putInt(it, offset, value)
            rewriteCrc(it)
        }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).putInt(value)
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).int

    private fun rewriteCrc(bytes: ByteArray) {
        val crc = CRC32().apply {
            update(bytes, CacheBounds.ENVELOPE_BYTES, bytes.size - CacheBounds.ENVELOPE_BYTES)
        }
        putInt(bytes, 16, crc.value.toInt())
    }
}
