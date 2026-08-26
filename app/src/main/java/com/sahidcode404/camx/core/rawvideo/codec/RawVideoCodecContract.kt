package com.sahidcode404.camx.core.rawvideo.codec

import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterHasher
import com.sahidcode404.camx.core.camera.acquisition.InterpretableSensorDomain
import com.sahidcode404.camx.core.camera.acquisition.M1AcquisitionLimits
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32

object RawVideoCodecLimits {
    const val MAX_FRAME_BYTES = M1AcquisitionLimits.MAX_CANONICAL_RASTER_BYTES
    const val MAX_ENCODED_FRAME_BYTES = 603_979_776L // ceil(512 MiB * 9 / 8)
    const val MAX_CODEC_PARAMETERS = 16
    const val MAX_PARAMETER_KEY_BYTES = 128
    const val MAX_PARAMETER_VALUE_BYTES = 256
}

enum class RawVideoCodecPretransform {
    NONE,
    BYTE_DELTA_MOD_256,
}

data class RawVideoCodecParameter(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Codec parameter key must be nonblank" }
        require(key.toByteArray(StandardCharsets.UTF_8).size <= RawVideoCodecLimits.MAX_PARAMETER_KEY_BYTES) {
            "Codec parameter key exceeds the M2B bound"
        }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= RawVideoCodecLimits.MAX_PARAMETER_VALUE_BYTES) {
            "Codec parameter value exceeds the M2B bound"
        }
    }
}

data class RawVideoCodecDescriptor(
    val family: String,
    val version: Int,
    val pretransform: RawVideoCodecPretransform,
    parameters: List<RawVideoCodecParameter> = emptyList(),
    val independentFrameDecode: Boolean = true,
    val independentTileDecode: Boolean = false,
) {
    val parameters: List<RawVideoCodecParameter> = Collections.unmodifiableList(
        ArrayList(parameters.sortedWith(compareBy(RawVideoCodecParameter::key, RawVideoCodecParameter::value))),
    )

    init {
        require(family.isNotBlank()) { "Codec family must be named" }
        require(version > 0) { "Codec version must be positive" }
        require(parameters.size <= RawVideoCodecLimits.MAX_CODEC_PARAMETERS) {
            "Codec parameter count exceeds the M2B bound"
        }
        require(parameters.map(RawVideoCodecParameter::key).distinct().size == parameters.size) {
            "Codec parameter keys must be unique"
        }
        require(independentFrameDecode) { "Every RAW-video codec must support independent frame decode" }
    }
}

data class CodecReservation(
    val decodedBytes: Long,
    val maximumEncodedBytes: Long,
    val encodeWorkspaceBytes: Long,
    val decodeWorkspaceBytes: Long,
) {
    init {
        require(decodedBytes in 1L..RawVideoCodecLimits.MAX_FRAME_BYTES) {
            "Decoded RAW-video frame exceeds the M2B bound"
        }
        require(maximumEncodedBytes in 1L..RawVideoCodecLimits.MAX_ENCODED_FRAME_BYTES) {
            "Encoded RAW-video frame reservation exceeds the M2B bound"
        }
        require(encodeWorkspaceBytes >= 0L && decodeWorkspaceBytes >= 0L) {
            "Codec workspace cannot be negative"
        }
        checkedCodecAdd(maximumEncodedBytes, encodeWorkspaceBytes)
        checkedCodecAdd(decodedBytes, decodeWorkspaceBytes)
    }

    val maximumEncodePhaseBytes: Long
        get() = checkedCodecAdd(maximumEncodedBytes, encodeWorkspaceBytes)

    val maximumDecodePhaseBytes: Long
        get() = checkedCodecAdd(decodedBytes, decodeWorkspaceBytes)
}

/** Immutable one-frame canonical sensor raster accepted by the frozen RAW-video codec seam. */
class CanonicalCodecFrame(
    val representation: RepresentationDescriptor,
    val canonicalRaster: CanonicalRasterDigest,
    payload: ByteArray,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(representation.representation is InterpretableSensorDomain) {
            "RAW-video codecs accept only interpretable sensor-domain canonical rasters"
        }
        require(payloadBytes.isNotEmpty()) { "Canonical codec frame cannot be empty" }
        require(payloadBytes.size.toLong() <= RawVideoCodecLimits.MAX_FRAME_BYTES) {
            "Canonical codec frame exceeds the M2B bound"
        }
        require(representation.canonicalByteCount() == payloadBytes.size.toLong()) {
            "Representation canonical byte count does not match the codec frame"
        }
        require(canonicalRaster.byteCount == payloadBytes.size.toLong()) {
            "Canonical digest byte count does not match the codec frame"
        }
        require(canonicalRaster.sha256 == codecSha256Hex(payloadBytes)) {
            "Canonical codec frame digest mismatch"
        }
    }

    val byteCount: Long
        get() = payloadBytes.size.toLong()

    internal fun copyPayload(): ByteArray = payloadBytes.copyOf()
}

data class EncodedFrameHeader(
    val codec: RawVideoCodecDescriptor,
    val decodedByteCount: Long,
    val encodedByteCount: Long,
    val encodedBitCount: Long,
    val encodedCrc32: Long,
    val encodedSha256: String,
    val decodedRasterSha256: String,
    val representationDescriptorSha256: String,
    frameParameters: List<RawVideoCodecParameter> = emptyList(),
) {
    val frameParameters: List<RawVideoCodecParameter> = Collections.unmodifiableList(
        ArrayList(frameParameters.sortedWith(compareBy(RawVideoCodecParameter::key, RawVideoCodecParameter::value))),
    )

    init {
        require(decodedByteCount in 1L..RawVideoCodecLimits.MAX_FRAME_BYTES) {
            "Decoded frame length exceeds the M2B bound"
        }
        require(encodedByteCount in 1L..RawVideoCodecLimits.MAX_ENCODED_FRAME_BYTES) {
            "Encoded frame length exceeds the M2B bound"
        }
        val maximumBits = checkedCodecMultiply(encodedByteCount, 8L)
        require(encodedBitCount in 1L..maximumBits) { "Encoded bit count is outside the payload" }
        require(encodedBitCount > checkedCodecMultiply(encodedByteCount - 1L, 8L)) {
            "Encoded payload contains an unaccounted whole trailing byte"
        }
        require(encodedCrc32 in 0L..0xffff_ffffL) { "Encoded CRC32 is outside uint32" }
        requireSha256(encodedSha256, "Encoded")
        requireSha256(decodedRasterSha256, "Decoded raster")
        requireSha256(representationDescriptorSha256, "Representation descriptor")
        require(frameParameters.size <= RawVideoCodecLimits.MAX_CODEC_PARAMETERS) {
            "Per-frame codec parameter count exceeds the M2B bound"
        }
        require(frameParameters.map(RawVideoCodecParameter::key).distinct().size == frameParameters.size) {
            "Per-frame codec parameter keys must be unique"
        }
    }
}

internal class EncodedFramePacket(
    val header: EncodedFrameHeader,
    payload: ByteArray,
) {
    private val bytes = payload.copyOf()

    init {
        require(bytes.size.toLong() == header.encodedByteCount) {
            "Encoded payload length does not match its header"
        }
    }

    fun copyPayload(): ByteArray = bytes.copyOf()
}

/** Move-only encoded frame ownership between a codec and a container adapter. */
class EncodedFrameLease internal constructor(
    val header: EncodedFrameHeader,
    payload: ByteArray,
) : AutoCloseable {
    private var bytes: ByteArray? = payload.copyOf()
    private var closed = false

    init {
        require(payload.size.toLong() == header.encodedByteCount) {
            "Encoded lease payload length does not match its header"
        }
    }

    @Synchronized
    internal fun take(): EncodedFramePacket {
        check(!closed) { "Encoded frame lease is closed" }
        val owned = bytes ?: error("Encoded frame lease was already moved")
        bytes = null
        return EncodedFramePacket(header, owned)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        bytes = null
    }

    internal companion object {
        fun create(header: EncodedFrameHeader, payload: ByteArray): EncodedFrameLease =
            EncodedFrameLease(header, payload)
    }
}

/** Move-only decoded canonical raster produced after all codec integrity checks pass. */
class DecodedFrameLease internal constructor(
    val canonicalRaster: CanonicalRasterDigest,
    payload: ByteArray,
) : AutoCloseable {
    private var bytes: ByteArray? = payload.copyOf()
    private var closed = false

    init {
        require(payload.size.toLong() == canonicalRaster.byteCount)
        require(codecSha256Hex(payload) == canonicalRaster.sha256)
    }

    @Synchronized
    fun take(): ByteArray {
        check(!closed) { "Decoded frame lease is closed" }
        val owned = bytes ?: error("Decoded frame lease was already moved")
        bytes = null
        return owned
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        bytes = null
    }
}

interface RawVideoCodec {
    val descriptor: RawVideoCodecDescriptor

    /** Admission-safe worst-case reservation. This must be computed before encode/decode allocation. */
    fun reservationFor(decodedBytes: Long): CodecReservation

    fun encode(frame: CanonicalCodecFrame): EncodedFrameLease

    /** Consumes the encoded lease. A malformed frame fails closed and never returns partial output. */
    fun decode(
        encoded: EncodedFrameLease,
        expectedRepresentation: RepresentationDescriptor,
    ): DecodedFrameLease
}

internal fun verifyEncodedPacket(
    packet: EncodedFramePacket,
    expectedCodec: RawVideoCodecDescriptor,
    expectedRepresentation: RepresentationDescriptor,
): ByteArray {
    require(expectedRepresentation.representation is InterpretableSensorDomain) {
        "Decoded RAW-video target must remain interpretable sensor-domain evidence"
    }
    val header = packet.header
    require(header.codec == expectedCodec) { "Encoded frame codec identity mismatch" }
    require(
        header.representationDescriptorSha256 == CanonicalRasterHasher.descriptorSha256(expectedRepresentation),
    ) { "Encoded frame representation descriptor mismatch" }
    require(header.decodedByteCount == expectedRepresentation.canonicalByteCount()) {
        "Encoded frame decoded size does not match the representation descriptor"
    }
    val payload = packet.copyPayload()
    require(payload.size.toLong() == header.encodedByteCount) { "Encoded payload length mismatch" }
    require(codecCrc32(payload) == header.encodedCrc32) { "Encoded payload CRC32 mismatch" }
    require(codecSha256Hex(payload) == header.encodedSha256) { "Encoded payload SHA-256 mismatch" }
    return payload
}

internal fun codecCrc32(bytes: ByteArray): Long = CRC32().run {
    update(bytes)
    value
}

internal fun codecSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).codecHexLower()

internal fun checkedCodecAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("Codec size arithmetic overflow", error)
}

internal fun checkedCodecMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("Codec size arithmetic overflow", error)
}

internal fun ceilCodecDiv(value: Long, divisor: Long): Long {
    require(value >= 0L && divisor > 0L)
    return if (value == 0L) 0L else 1L + (value - 1L) / divisor
}

private fun requireSha256(value: String, label: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label digest must be lowercase SHA-256"
    }
}

private fun ByteArray.codecHexLower(): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        result[index * 2] = alphabet[unsigned ushr 4]
        result[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return String(result)
}
