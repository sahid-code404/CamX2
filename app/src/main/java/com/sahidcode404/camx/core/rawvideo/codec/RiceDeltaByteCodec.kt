package com.sahidcode404.camx.core.rawvideo.codec

import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterHasher
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor

/**
 * M2B reference compressed-codec candidate.
 *
 * Each frame is independent. Bytes are first converted to modulo-256 forward deltas, then encoded
 * with one per-frame Rice parameter k in 0..7. The smallest exact bit-count k is chosen by a bounded
 * 256-bin histogram. k=7 proves a hard <=9 bits/source-byte expansion ceiling, so admission never
 * depends on observed compression ratio and PACKED_NONE remains a separately reserved fallback.
 */
object RiceDeltaByteCodec : RawVideoCodec {
    private const val FRAME_PARAMETER_K = "rice-k"

    override val descriptor = RawVideoCodecDescriptor(
        family = "RICE_DELTA_BYTE",
        version = 1,
        pretransform = RawVideoCodecPretransform.BYTE_DELTA_MOD_256,
        parameters = listOf(
            RawVideoCodecParameter("bit-order", "msb0"),
            RawVideoCodecParameter("delta-modulus", "256"),
            RawVideoCodecParameter("k-range", "0..7"),
        ),
    )

    override fun reservationFor(decodedBytes: Long): CodecReservation {
        require(decodedBytes in 1L..RawVideoCodecLimits.MAX_FRAME_BYTES) {
            "Rice-delta decoded size exceeds the M2B bound"
        }
        val maximumBits = checkedCodecMultiply(decodedBytes, 9L)
        val maximumEncoded = ceilCodecDiv(maximumBits, 8L)
        require(maximumEncoded <= RawVideoCodecLimits.MAX_ENCODED_FRAME_BYTES) {
            "Rice-delta worst-case output exceeds the M2B bound"
        }
        return CodecReservation(
            decodedBytes = decodedBytes,
            maximumEncodedBytes = maximumEncoded,
            encodeWorkspaceBytes = 256L * java.lang.Long.BYTES,
            decodeWorkspaceBytes = 0L,
        )
    }

    override fun encode(frame: CanonicalCodecFrame): EncodedFrameLease {
        val source = frame.copyPayload()
        val reservation = reservationFor(source.size.toLong())
        val histogram = LongArray(256)
        var previous = 0
        source.forEach { byte ->
            val current = byte.toInt() and 0xff
            val delta = (current - previous) and 0xff
            histogram[delta]++
            previous = current
        }
        val choice = chooseK(histogram)
        val encodedBytes = ceilCodecDiv(choice.bitCount, 8L)
        require(encodedBytes <= reservation.maximumEncodedBytes) {
            "Rice-delta encoded output exceeded its admission reservation"
        }
        require(encodedBytes <= Int.MAX_VALUE.toLong()) { "Rice-delta output cannot be JVM-addressed" }
        val payload = ByteArray(encodedBytes.toInt())
        val writer = FixedBitWriter(payload, choice.bitCount)
        previous = 0
        source.forEach { byte ->
            val current = byte.toInt() and 0xff
            val delta = (current - previous) and 0xff
            val quotient = delta ushr choice.k
            repeat(quotient) { writer.writeBit(0) }
            writer.writeBit(1)
            writer.writeBits(delta and ((1 shl choice.k) - 1), choice.k)
            previous = current
        }
        check(writer.positionBits == choice.bitCount) { "Rice-delta encoder bit accounting diverged" }

        val header = EncodedFrameHeader(
            codec = descriptor,
            decodedByteCount = source.size.toLong(),
            encodedByteCount = payload.size.toLong(),
            encodedBitCount = choice.bitCount,
            encodedCrc32 = codecCrc32(payload),
            encodedSha256 = codecSha256Hex(payload),
            decodedRasterSha256 = frame.canonicalRaster.sha256,
            representationDescriptorSha256 = CanonicalRasterHasher.descriptorSha256(frame.representation),
            frameParameters = listOf(RawVideoCodecParameter(FRAME_PARAMETER_K, choice.k.toString())),
        )
        return EncodedFrameLease.create(header, payload)
    }

    override fun decode(
        encoded: EncodedFrameLease,
        expectedRepresentation: RepresentationDescriptor,
    ): DecodedFrameLease {
        val header = encoded.header
        val reservation = reservationFor(header.decodedByteCount)
        require(header.encodedByteCount <= reservation.maximumEncodedBytes) {
            "Rice-delta payload exceeds the admission-safe expansion bound"
        }
        val k = decodeK(header)
        val packet = encoded.take()
        val payload = verifyEncodedPacket(packet, descriptor, expectedRepresentation)
        require(header.decodedByteCount <= Int.MAX_VALUE.toLong()) {
            "Rice-delta decoded frame cannot be JVM-addressed"
        }
        val decoded = ByteArray(header.decodedByteCount.toInt())
        val reader = FixedBitReader(payload, header.encodedBitCount)
        var previous = 0
        for (index in decoded.indices) {
            var quotient = 0
            val maximumQuotient = 255 ushr k
            while (true) {
                val bit = reader.readBit()
                if (bit == 1) break
                quotient++
                require(quotient <= maximumQuotient) {
                    "Rice-delta unary quotient exceeds one byte"
                }
            }
            val remainder = reader.readBits(k)
            val delta = (quotient shl k) or remainder
            require(delta in 0..255) { "Rice-delta symbol exceeds one byte" }
            val current = (previous + delta) and 0xff
            decoded[index] = current.toByte()
            previous = current
        }
        require(reader.positionBits == header.encodedBitCount) {
            "Rice-delta frame has trailing or unconsumed encoded bits"
        }
        require(codecSha256Hex(decoded) == header.decodedRasterSha256) {
            "Rice-delta decoded raster digest mismatch"
        }
        return DecodedFrameLease(
            CanonicalRasterDigest(header.decodedRasterSha256, header.decodedByteCount),
            decoded,
        )
    }

    private fun chooseK(histogram: LongArray): RiceChoice {
        require(histogram.size == 256)
        var bestK = 7
        var bestBits = Long.MAX_VALUE
        for (k in 0..7) {
            var bits = 0L
            for (delta in 0..255) {
                val count = histogram[delta]
                if (count == 0L) continue
                val bitsPerSymbol = (delta ushr k) + 1 + k
                bits = checkedCodecAdd(bits, checkedCodecMultiply(count, bitsPerSymbol.toLong()))
            }
            if (bits < bestBits) {
                bestBits = bits
                bestK = k
            }
        }
        require(bestBits > 0L) { "Rice-delta cannot encode an empty frame" }
        return RiceChoice(bestK, bestBits)
    }

    private fun decodeK(header: EncodedFrameHeader): Int {
        require(header.frameParameters.size == 1 && header.frameParameters.single().key == FRAME_PARAMETER_K) {
            "Rice-delta frame requires exactly one rice-k parameter"
        }
        val k = header.frameParameters.single().value.toIntOrNull()
            ?: throw IllegalArgumentException("Rice-delta rice-k is not an integer")
        require(k in 0..7) { "Rice-delta rice-k is outside 0..7" }
        return k
    }

    private data class RiceChoice(val k: Int, val bitCount: Long)
}

private class FixedBitWriter(
    private val destination: ByteArray,
    private val bitLimit: Long,
) {
    var positionBits: Long = 0L
        private set

    fun writeBit(bit: Int) {
        require(bit == 0 || bit == 1)
        check(positionBits < bitLimit) { "Rice-delta writer exceeded the precomputed bit count" }
        val byteIndex = (positionBits ushr 3).toInt()
        val bitInByte = 7 - (positionBits and 7L).toInt()
        if (bit == 1) {
            destination[byteIndex] = (destination[byteIndex].toInt() or (1 shl bitInByte)).toByte()
        }
        positionBits++
    }

    fun writeBits(value: Int, count: Int) {
        require(count in 0..7)
        for (shift in count - 1 downTo 0) writeBit((value ushr shift) and 1)
    }
}

private class FixedBitReader(
    private val source: ByteArray,
    private val bitLimit: Long,
) {
    var positionBits: Long = 0L
        private set

    init {
        require(bitLimit > 0L && bitLimit <= checkedCodecMultiply(source.size.toLong(), 8L)) {
            "Rice-delta bit limit is outside the encoded payload"
        }
    }

    fun readBit(): Int {
        require(positionBits < bitLimit) { "Rice-delta payload is truncated" }
        val byteIndex = (positionBits ushr 3).toInt()
        val bitInByte = 7 - (positionBits and 7L).toInt()
        val bit = (source[byteIndex].toInt() ushr bitInByte) and 1
        positionBits++
        return bit
    }

    fun readBits(count: Int): Int {
        require(count in 0..7)
        var value = 0
        repeat(count) { value = (value shl 1) or readBit() }
        return value
    }
}
