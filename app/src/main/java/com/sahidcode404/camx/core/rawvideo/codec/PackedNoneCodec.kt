package com.sahidcode404.camx.core.rawvideo.codec

import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterHasher
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor

/** Mandatory frozen admission-safe reversible baseline. No compression and no sample transform. */
object PackedNoneCodec : RawVideoCodec {
    override val descriptor = RawVideoCodecDescriptor(
        family = "PACKED_NONE",
        version = 1,
        pretransform = RawVideoCodecPretransform.NONE,
    )

    override fun reservationFor(decodedBytes: Long): CodecReservation {
        require(decodedBytes in 1L..RawVideoCodecLimits.MAX_FRAME_BYTES) {
            "PACKED_NONE decoded size exceeds the M2B bound"
        }
        return CodecReservation(
            decodedBytes = decodedBytes,
            maximumEncodedBytes = decodedBytes,
            encodeWorkspaceBytes = 0L,
            decodeWorkspaceBytes = 0L,
        )
    }

    override fun encode(frame: CanonicalCodecFrame): EncodedFrameLease {
        val reservation = reservationFor(frame.byteCount)
        val payload = frame.copyPayload()
        check(payload.size.toLong() <= reservation.maximumEncodedBytes)
        val header = EncodedFrameHeader(
            codec = descriptor,
            decodedByteCount = payload.size.toLong(),
            encodedByteCount = payload.size.toLong(),
            encodedBitCount = checkedCodecMultiply(payload.size.toLong(), 8L),
            encodedCrc32 = codecCrc32(payload),
            encodedSha256 = codecSha256Hex(payload),
            decodedRasterSha256 = frame.canonicalRaster.sha256,
            representationDescriptorSha256 = CanonicalRasterHasher.descriptorSha256(frame.representation),
        )
        return EncodedFrameLease.create(header, payload)
    }

    override fun decode(
        encoded: EncodedFrameLease,
        expectedRepresentation: RepresentationDescriptor,
    ): DecodedFrameLease {
        val packet = encoded.take()
        val payload = verifyEncodedPacket(packet, descriptor, expectedRepresentation)
        val header = packet.header
        require(header.frameParameters.isEmpty()) { "PACKED_NONE has no per-frame codec parameters" }
        require(header.encodedByteCount == header.decodedByteCount) {
            "PACKED_NONE encoded and decoded lengths must match"
        }
        require(header.encodedBitCount == checkedCodecMultiply(header.encodedByteCount, 8L)) {
            "PACKED_NONE payload must account for every encoded bit"
        }
        require(codecSha256Hex(payload) == header.decodedRasterSha256) {
            "PACKED_NONE decoded raster digest mismatch"
        }
        return DecodedFrameLease(
            CanonicalRasterDigest(header.decodedRasterSha256, header.decodedByteCount),
            payload,
        )
    }
}
