package com.sahidcode404.camx.core.camera.acquisition

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SourcePlane(
    val planeIndex: Int,
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()

    init {
        require(planeIndex >= 0) { "Plane index cannot be negative" }
        require(this.bytes.size.toLong() <= M1AcquisitionLimits.MAX_SOURCE_PLANE_BYTES) {
            "Source plane exceeds the M1 bound"
        }
    }

    val byteCount: Int
        get() = bytes.size

    internal fun updateDigest(digest: MessageDigest, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }
}

data class CanonicalRasterDigest(
    val sha256: String,
    val byteCount: Long,
) {
    init {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Canonical raster digest must be lowercase SHA-256"
        }
        require(byteCount > 0L) { "Canonical raster must contain bytes" }
    }
}

object CanonicalRasterHasher {
    fun hash(
        descriptor: RepresentationDescriptor,
        sourcePlanes: List<SourcePlane>,
    ): CanonicalRasterDigest {
        require(descriptor.representation is InterpretableSensorDomain) {
            "Only interpretable sensor-domain evidence has a canonical sensor raster"
        }
        require(sourcePlanes.size == descriptor.planes.size) {
            "Source plane count does not match the representation descriptor"
        }
        val byIndex = sourcePlanes.associateBy(SourcePlane::planeIndex)
        require(byIndex.size == sourcePlanes.size) { "Source plane indices must be unique" }
        require(byIndex.keys == descriptor.planes.map { it.planeIndex }.toSet()) {
            "Source plane indices do not match the representation descriptor"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        descriptor.planes.forEach { planeDescriptor ->
            val plane = checkNotNull(byIndex[planeDescriptor.planeIndex])
            val requiredBytes = planeDescriptor.requiredSourceBytes()
            require(requiredBytes <= plane.byteCount.toLong()) {
                "Plane ${planeDescriptor.planeIndex} is shorter than its declared source extent"
            }
            repeat(planeDescriptor.rowCount) { row ->
                val rowOffset = safeRowOffset(planeDescriptor, row)
                val rowLength = planeDescriptor.meaningfulRowBytes.toInt()
                require(rowOffset <= Int.MAX_VALUE.toLong()) { "Row offset exceeds JVM array addressing" }
                val end = rowOffset + rowLength.toLong()
                require(end <= plane.byteCount.toLong()) { "Meaningful row exceeds source plane bytes" }
                plane.updateDigest(digest, rowOffset.toInt(), rowLength)
                byteCount = Math.addExact(byteCount, rowLength.toLong())
                require(byteCount <= M1AcquisitionLimits.MAX_CANONICAL_RASTER_BYTES) {
                    "Canonical raster exceeds the M1 bound"
                }
            }
        }
        check(byteCount == descriptor.canonicalByteCount()) {
            "Canonical raster byte count diverged from the immutable descriptor"
        }
        return CanonicalRasterDigest(digest.digest().toLowerHex(), byteCount)
    }

    fun descriptorSha256(descriptor: RepresentationDescriptor): String =
        MessageDigest.getInstance("SHA-256").digest(descriptor.canonicalDescriptorBytes()).toLowerHex()

    private fun safeRowOffset(descriptor: AcquisitionPlaneDescriptor, row: Int): Long = try {
        Math.addExact(
            descriptor.offsetBytes,
            Math.multiplyExact(row.toLong(), descriptor.rowStrideBytes),
        )
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Row address overflow", error)
    }
}

internal fun RepresentationDescriptor.canonicalDescriptorBytes(): ByteArray {
    val sink = ByteArrayOutputStream()
    DataOutputStream(sink).use { output ->
        output.writeInt(1)
        output.writeBoundedUtf8(representationName())
        output.writeBoundedUtf8(sourceFormat.name)
        output.writeBoundedUtf8(packing.name)
        output.writeInt(storedBits)
        output.writeInt(effectiveBits)
        output.writeInt(size.width)
        output.writeInt(size.height)
        output.writeInt(activeArea.left)
        output.writeInt(activeArea.top)
        output.writeInt(activeArea.width)
        output.writeInt(activeArea.height)
        output.writeInt(planes.size)
        planes.forEach { plane ->
            output.writeInt(plane.planeIndex)
            output.writeLong(plane.offsetBytes)
            output.writeLong(plane.rowStrideBytes)
            output.writeLong(plane.meaningfulRowBytes)
            output.writeInt(plane.rowCount)
            output.writeInt(plane.pixelStrideBytes)
        }
        output.writeBoolean(cfaPattern != null)
        cfaPattern?.let { output.writeBoundedUtf8(it.name) }
        output.writeBoundedUtf8(sensorPixelMode.name)
        output.writeNullableUtf8(colorCalibrationIdentity)
        output.writeNullableUtf8(calibration.identity)
        output.writeNullableUtf8(calibration.version)
        output.writeLong(java.lang.Double.doubleToLongBits(calibration.confidence))
        output.writeBoundedUtf8(sourceApi.name)
        output.writeInt(interpretationFields.size)
        interpretationFields.forEach { field ->
            output.writeBoundedUtf8(field.key)
            output.writeBoundedUtf8(field.value)
        }
    }
    return sink.toByteArray()
}

private fun DataOutputStream.writeNullableUtf8(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeBoundedUtf8)
}

private fun DataOutputStream.writeBoundedUtf8(value: String) {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    require(encoded.size <= 64 * 1024) { "Canonical descriptor token is unexpectedly large" }
    writeInt(encoded.size)
    write(encoded)
}

internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        result[index * 2] = alphabet[unsigned ushr 4]
        result[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return String(result)
}
