package com.sahidcode404.camx.core.rawvideo.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedNoneCodecTest {
    @Test
    fun packedNoneRoundTripIsBitExactAndDescriptorBound() {
        val payload = rampPayload(4096)
        val frame = m2bFrame(payload)
        val encoded = PackedNoneCodec.encode(frame)

        assertEquals("PACKED_NONE", encoded.header.codec.family)
        assertEquals(payload.size.toLong(), encoded.header.encodedByteCount)
        assertEquals(payload.size.toLong(), encoded.header.decodedByteCount)
        assertEquals(payload.size.toLong() * 8L, encoded.header.encodedBitCount)

        val decoded = PackedNoneCodec.decode(encoded, frame.representation)
        assertEquals(frame.canonicalRaster, decoded.canonicalRaster)
        assertArrayEquals(payload, decoded.take())
    }

    @Test
    fun canonicalFrameDefensivelyFreezesSourceBytes() {
        val payload = rampPayload(128)
        val expected = payload.copyOf()
        val frame = m2bFrame(payload)
        payload.fill(0)

        val decoded = PackedNoneCodec.decode(PackedNoneCodec.encode(frame), frame.representation)
        assertArrayEquals(expected, decoded.take())
    }

    @Test
    fun packedNoneRejectsCorruptEncodedPayload() {
        val frame = m2bFrame(rampPayload(256))
        val original = PackedNoneCodec.encode(frame)
        val packet = original.take()
        val corrupted = packet.copyPayload().also { it[0] = (it[0].toInt() xor 0x40).toByte() }
        val corruptLease = EncodedFrameLease.create(packet.header, corrupted)

        assertThrows(IllegalArgumentException::class.java) {
            PackedNoneCodec.decode(corruptLease, frame.representation)
        }
    }

    @Test
    fun encodedLeaseMovesExactlyOnce() {
        val frame = m2bFrame(rampPayload(64))
        val lease = PackedNoneCodec.encode(frame)
        val packet = lease.take()
        assertEquals(64L, packet.header.encodedByteCount)
        assertThrows(IllegalStateException::class.java) { lease.take() }
        lease.close()
        lease.close()
    }

    @Test
    fun packedNoneReservationIsExactlyDecodedSize() {
        val reservation = PackedNoneCodec.reservationFor(1024L)
        assertEquals(1024L, reservation.maximumEncodedBytes)
        assertEquals(1024L, reservation.maximumEncodePhaseBytes)
        assertEquals(1024L, reservation.maximumDecodePhaseBytes)
        assertTrue(PackedNoneCodec.descriptor.independentFrameDecode)
    }
}
