package com.sahidcode404.camx.core.rawvideo.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RiceDeltaByteCodecTest {
    @Test
    fun structuredSensorBytesRoundTripBitExactlyAndCompress() {
        val payload = rampPayload(4096)
        val frame = m2bFrame(payload)
        val encoded = RiceDeltaByteCodec.encode(frame)

        assertEquals("RICE_DELTA_BYTE", encoded.header.codec.family)
        assertTrue(encoded.header.encodedByteCount < payload.size.toLong())
        assertEquals("rice-k", encoded.header.frameParameters.single().key)

        val decoded = RiceDeltaByteCodec.decode(encoded, frame.representation)
        assertEquals(frame.canonicalRaster, decoded.canonicalRaster)
        assertArrayEquals(payload, decoded.take())
    }

    @Test
    fun randomInputNeverExceedsNineBitPerByteAdmissionCeiling() {
        val payload = deterministicRandomPayload(8192, seed = 84)
        val frame = m2bFrame(payload)
        val reservation = RiceDeltaByteCodec.reservationFor(payload.size.toLong())
        val encoded = RiceDeltaByteCodec.encode(frame)

        assertTrue(encoded.header.encodedByteCount <= reservation.maximumEncodedBytes)
        assertTrue(encoded.header.encodedBitCount <= payload.size.toLong() * 9L)
        assertArrayEquals(payload, RiceDeltaByteCodec.decode(encoded, frame.representation).take())
    }

    @Test
    fun eachFrameDecodesIndependentlyWithoutPriorFrameState() {
        val first = m2bFrame(flatPayload(1024, 0x11))
        val secondPayload = flatPayload(1024, 0x77)
        val second = m2bFrame(secondPayload)
        val ignoredFirst = RiceDeltaByteCodec.encode(first)
        val encodedSecond = RiceDeltaByteCodec.encode(second)
        ignoredFirst.close()

        assertArrayEquals(
            secondPayload,
            RiceDeltaByteCodec.decode(encodedSecond, second.representation).take(),
        )
    }

    @Test
    fun invalidRiceParameterFailsBeforeDecodeAllocation() {
        val frame = m2bFrame(rampPayload(512))
        val packet = RiceDeltaByteCodec.encode(frame).take()
        val invalidHeader = packet.header.copy(
            frameParameters = listOf(RawVideoCodecParameter("rice-k", "8")),
        )
        val invalid = EncodedFrameLease.create(invalidHeader, packet.copyPayload())

        assertThrows(IllegalArgumentException::class.java) {
            RiceDeltaByteCodec.decode(invalid, frame.representation)
        }
    }

    @Test
    fun structurallyTruncatedBitstreamFailsClosed() {
        val frame = m2bFrame(rampPayload(1024))
        val packet = RiceDeltaByteCodec.encode(frame).take()
        val original = packet.copyPayload()
        assertTrue(original.size > 1)
        val truncated = original.copyOf(original.size - 1)
        val truncatedHeader = packet.header.copy(
            encodedByteCount = truncated.size.toLong(),
            encodedBitCount = truncated.size.toLong() * 8L,
            encodedCrc32 = codecCrc32(truncated),
            encodedSha256 = codecSha256Hex(truncated),
        )
        val invalid = EncodedFrameLease.create(truncatedHeader, truncated)

        assertThrows(IllegalArgumentException::class.java) {
            RiceDeltaByteCodec.decode(invalid, frame.representation)
        }
    }

    @Test
    fun decodedRasterDigestMismatchFailsClosed() {
        val frame = m2bFrame(rampPayload(256))
        val packet = RiceDeltaByteCodec.encode(frame).take()
        val invalidHeader = packet.header.copy(decodedRasterSha256 = "0".repeat(64))
        val invalid = EncodedFrameLease.create(invalidHeader, packet.copyPayload())

        assertThrows(IllegalArgumentException::class.java) {
            RiceDeltaByteCodec.decode(invalid, frame.representation)
        }
    }

    @Test
    fun maximumM1FrameReservationUsesLongArithmeticWithoutAllocation() {
        val reservation = RiceDeltaByteCodec.reservationFor(RawVideoCodecLimits.MAX_FRAME_BYTES)
        assertEquals(RawVideoCodecLimits.MAX_ENCODED_FRAME_BYTES, reservation.maximumEncodedBytes)
        assertEquals(2048L, reservation.encodeWorkspaceBytes)
        assertTrue(reservation.maximumEncodePhaseBytes > RawVideoCodecLimits.MAX_FRAME_BYTES)
    }
}
