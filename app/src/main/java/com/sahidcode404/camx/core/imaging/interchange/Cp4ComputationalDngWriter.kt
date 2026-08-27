package com.sahidcode404.camx.core.imaging.interchange

import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationBundle
import com.sahidcode404.camx.core.camera.raw.Cp2Matrix3x3Evidence
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusedCfa
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusionReport
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * CP4 writes the production-connected CP3 fused CFA as one standards-shaped float DNG.
 *
 * The writer consumes only immutable CP2/CP3 evidence. Camera2 objects never cross this boundary.
 * CP2 Camera2 rational matrices are written verbatim into their corresponding DNG tags; missing
 * mandatory color authority fails closed instead of fabricating metadata. The CP3 raster is already
 * black-subtracted linear sensor-domain signal, so the exported DNG BlackLevel is exactly zero.
 */
class Cp4ComputationalDngWriter {
    fun write(
        fused: Cp3FusedCfa,
        fusionReport: Cp3FusionReport,
        calibration: Cp2CalibrationBundle,
        uniqueCameraModel: String,
        orientation: Int,
        output: OutputStream,
        maxOutputBytes: Long = MAX_FILE_BYTES,
    ): Cp4ComputationalDngReceipt {
        require(fusionReport.success) { "CP4 requires successful CP3 fusion" }
        require(fusionReport.outputSha256 == fused.outputSha256) {
            "CP4 CP3 report/output identity mismatch"
        }
        require(calibration.report.success) { "CP4 requires successful exact CP2 calibration" }
        require(calibration.report.calibrationFingerprintSha256 == fusionReport.calibrationFingerprintSha256) {
            "CP4 CP2 calibration identity diverged from CP3"
        }
        require(orientation in setOf(1, 3, 6, 8)) { "CP4 orientation must be an orthogonal TIFF orientation" }
        val modelBytes = uniqueCameraModel.toByteArray(Charsets.US_ASCII)
        require(uniqueCameraModel.isNotBlank() && modelBytes.size <= MAX_UNIQUE_CAMERA_MODEL_BYTES) {
            "CP4 UniqueCameraModel must be nonblank and bounded"
        }
        require(uniqueCameraModel.all { it.code in 0x20..0x7e }) {
            "CP4 UniqueCameraModel must use printable ASCII"
        }

        val static = calibration.staticObservation
            ?: throw IllegalArgumentException("CP4 requires real Camera2 static calibration")
        val expectedCfa = cfaPattern(static.cfaArrangement)
            ?: throw IllegalArgumentException("CP4 supports the four public Bayer CFA arrangements only")
        require(expectedCfa == fused.cfaPattern) { "CP4 CFA authority diverged from CP3" }
        val active = static.activeArray
            ?: throw IllegalArgumentException("CP4 requires the real Camera2 active array")
        require(
            active.left == fused.activeArea.left &&
                active.top == fused.activeArea.top &&
                active.width == fused.activeArea.width &&
                active.height == fused.activeArea.height
        ) { "CP4 active-area authority diverged from CP3" }
        val black = static.blackLevels
            ?.takeIf { it.size == 4 }
            ?: throw IllegalArgumentException("CP4 requires the real Camera2 black-level pattern")
        val white = static.whiteLevel
            ?: throw IllegalArgumentException("CP4 requires the real Camera2 white level")
        val outputWhite = ceil(black.maxOf { white.toDouble() - it.toDouble() }).toLong()
        require(outputWhite in 1..0xffffL) { "CP4 U16 output white level is not representable in DNG" }

        val color1 = static.colorTransform1
            ?: throw IllegalArgumentException("CP4 cannot write a color CFA DNG without SENSOR_COLOR_TRANSFORM1")
        val illuminant1 = static.referenceIlluminant1
            ?: throw IllegalArgumentException("CP4 cannot write ColorMatrix1 without reference illuminant 1")
        require(illuminant1 in 1..0xffff) { "CP4 reference illuminant 1 is outside the DNG field" }
        val color2 = static.colorTransform2
        val illuminant2 = static.referenceIlluminant2
        require((color2 == null) == (illuminant2 == null)) {
            "CP4 second color matrix and illuminant must either both be present or both be absent"
        }
        require(illuminant2 == null || illuminant2 in 1..0xffff) {
            "CP4 reference illuminant 2 is outside the DNG field"
        }

        val manifest = privateManifest(fusionReport, fused).toByteArray(Charsets.UTF_8)
        require(manifest.isNotEmpty() && manifest.size <= MAX_PRIVATE_MANIFEST_BYTES) {
            "CP4 private manifest is empty or exceeds its bound"
        }
        val width = fused.activeArea.width
        val height = fused.activeArea.height
        val pixels = checkedMultiply(width.toLong(), height.toLong(), "CP4 pixel count overflow")
        require(pixels in 1..MAX_IMAGE_PIXELS) { "CP4 raster exceeds the bounded writer" }
        require(pixels <= Int.MAX_VALUE.toLong()) { "CP4 raster cannot be addressed by the current JVM implementation" }
        val imageBytes = checkedMultiply(pixels, UINT16_BYTES, "CP4 U16 raster byte count overflow")
        val rowsPerStrip = minOf(ROWS_PER_STRIP, height)
        val stripCount = ((height.toLong() + rowsPerStrip - 1L) / rowsPerStrip).toInt()
        require(stripCount in 1..MAX_STRIPS) { "CP4 strip table exceeds its bound" }
        val requiredUpperBound = checkedAdd(
            checkedAdd(MAX_METADATA_BYTES, manifest.size.toLong(), "CP4 output bound overflow"),
            checkedAdd(imageBytes, 16L, "CP4 output bound overflow"),
            "CP4 output bound overflow",
        )
        require(maxOutputBytes in requiredUpperBound..MAX_FILE_BYTES) {
            "CP4 output budget does not cover the proven worst-case extent"
        }

        val stripByteCounts = LongArray(stripCount) { strip ->
            val firstRow = strip * rowsPerStrip
            val rowCount = minOf(rowsPerStrip, height - firstRow)
            checkedMultiply(
                checkedMultiply(rowCount.toLong(), width.toLong(), "CP4 strip pixel count overflow"),
                UINT16_BYTES,
                "CP4 strip byte count overflow",
            )
        }
        check(stripByteCounts.sum() == imageBytes)
        val stripOffsetsPayload = ByteArray(stripCount * 4)
        val entries = ArrayList<TiffEntry>()
        entries += longEntry(TAG_IMAGE_WIDTH, width.toLong())
        entries += longEntry(TAG_IMAGE_LENGTH, height.toLong())
        entries += shortEntry(TAG_BITS_PER_SAMPLE, 16)
        entries += shortEntry(TAG_COMPRESSION, COMPRESSION_NONE)
        entries += shortEntry(TAG_PHOTOMETRIC_INTERPRETATION, PHOTOMETRIC_CFA)
        entries += TiffEntry(TAG_STRIP_OFFSETS, TYPE_LONG, stripCount.toLong(), stripOffsetsPayload)
        entries += shortEntry(TAG_ORIENTATION, orientation)
        entries += shortEntry(TAG_SAMPLES_PER_PIXEL, 1)
        entries += longEntry(TAG_ROWS_PER_STRIP, rowsPerStrip.toLong())
        entries += TiffEntry(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, stripCount.toLong(), uint32ArrayBytes(stripByteCounts))
        entries += shortEntry(TAG_PLANAR_CONFIGURATION, PLANAR_CHUNKY)
        entries += asciiEntry(TAG_SOFTWARE, "CamX2 CP4")
        entries += shortEntry(TAG_SAMPLE_FORMAT, SAMPLE_FORMAT_UNSIGNED_INT)
        entries += TiffEntry(TAG_CFA_REPEAT_PATTERN_DIM, TYPE_SHORT, 2L, shortArrayBytes(intArrayOf(2, 2)))
        entries += TiffEntry(TAG_CFA_PATTERN, TYPE_BYTE, 4L, cfaPatternBytes(fused.cfaPattern, active.left, active.top))
        entries += TiffEntry(TAG_DNG_VERSION, TYPE_BYTE, 4L, byteArrayOf(1, 4, 0, 0))
        entries += TiffEntry(TAG_DNG_BACKWARD_VERSION, TYPE_BYTE, 4L, byteArrayOf(1, 4, 0, 0))
        entries += asciiEntry(TAG_UNIQUE_CAMERA_MODEL, uniqueCameraModel)
        entries += TiffEntry(TAG_CFA_PLANE_COLOR, TYPE_BYTE, 3L, byteArrayOf(CFA_RED, CFA_GREEN, CFA_BLUE))
        entries += shortEntry(TAG_CFA_LAYOUT, CFA_LAYOUT_RECTANGULAR)
        entries += longEntry(TAG_BLACK_LEVEL, 0L)
        entries += longEntry(TAG_WHITE_LEVEL, outputWhite)
        entries += matrixEntry(TAG_COLOR_MATRIX_1, color1)
        static.calibrationTransform1?.let { entries += matrixEntry(TAG_CAMERA_CALIBRATION_1, it) }
        static.forwardMatrix1?.let { entries += matrixEntry(TAG_FORWARD_MATRIX_1, it) }
        entries += TiffEntry(
            tag = TAG_DNG_PRIVATE_DATA,
            type = TYPE_BYTE,
            count = manifest.size.toLong(),
            payload = null,
            externalKind = ExternalKind.PRIVATE,
        )
        entries += shortEntry(TAG_CALIBRATION_ILLUMINANT_1, illuminant1)
        entries += TiffEntry(
            TAG_ACTIVE_AREA,
            TYPE_LONG,
            4L,
            uint32ArrayBytes(longArrayOf(0L, 0L, height.toLong(), width.toLong())),
        )
        if (color2 != null && illuminant2 != null) {
            entries += matrixEntry(TAG_COLOR_MATRIX_2, color2)
            static.calibrationTransform2?.let { entries += matrixEntry(TAG_CAMERA_CALIBRATION_2, it) }
            static.forwardMatrix2?.let { entries += matrixEntry(TAG_FORWARD_MATRIX_2, it) }
            entries += shortEntry(TAG_CALIBRATION_ILLUMINANT_2, illuminant2)
        }
        entries.sortBy { it.tag }
        require(entries.size <= MAX_IFD_ENTRIES && entries.map { it.tag }.distinct().size == entries.size) {
            "CP4 TIFF entry table is invalid or exceeds its bound"
        }

        val ifdSize = 2L + entries.size.toLong() * TIFF_ENTRY_BYTES + 4L
        var extraCursor = align4(TIFF_HEADER_BYTES + ifdSize)
        entries.forEach { entry ->
            val bytes = entry.byteCount()
            require(bytes <= 0xffff_ffffL) { "CP4 TIFF entry exceeds classic TIFF" }
            if (bytes > 4L && entry.externalKind == ExternalKind.NORMAL) {
                entry.externalOffset = extraCursor
                extraCursor = align4(checkedAdd(extraCursor, bytes, "CP4 metadata offset overflow"))
            }
        }
        val privateOffset = align4(extraCursor)
        require(privateOffset <= MAX_METADATA_BYTES) { "CP4 TIFF metadata exceeds its bound" }
        val imageOffset = align4(
            checkedAdd(privateOffset, manifest.size.toLong(), "CP4 image offset overflow"),
        )
        val stripOffsets = LongArray(stripCount)
        var stripCursor = imageOffset
        stripByteCounts.forEachIndexed { index, count ->
            stripOffsets[index] = stripCursor
            stripCursor = checkedAdd(stripCursor, count, "CP4 strip offset overflow")
        }
        require(stripCursor <= 0xffff_ffffL && stripCursor <= maxOutputBytes) {
            "CP4 exact DNG layout exceeds classic TIFF or the admitted output budget"
        }
        val encodedOffsets = uint32ArrayBytes(stripOffsets)
        System.arraycopy(encodedOffsets, 0, stripOffsetsPayload, 0, encodedOffsets.size)
        entries.first { it.tag == TAG_DNG_PRIVATE_DATA }.externalOffset = privateOffset

        val metadata = ByteArray(privateOffset.toInt())
        metadata[0] = 'I'.code.toByte()
        metadata[1] = 'I'.code.toByte()
        putUInt16(metadata, 2, TIFF_MAGIC)
        putUInt32(metadata, 4, TIFF_HEADER_BYTES)
        putUInt16(metadata, TIFF_HEADER_BYTES.toInt(), entries.size)
        var entryOffset = TIFF_HEADER_BYTES.toInt() + 2
        entries.forEach { entry ->
            putUInt16(metadata, entryOffset, entry.tag)
            putUInt16(metadata, entryOffset + 2, entry.type)
            putUInt32(metadata, entryOffset + 4, entry.count)
            val bytes = entry.byteCount()
            if (bytes <= 4L) {
                val payload = checkNotNull(entry.payload) { "CP4 inline TIFF entry has no payload" }
                System.arraycopy(payload, 0, metadata, entryOffset + 8, payload.size)
            } else {
                putUInt32(metadata, entryOffset + 8, entry.externalOffset)
                if (entry.externalKind == ExternalKind.NORMAL) {
                    val payload = checkNotNull(entry.payload) { "CP4 external TIFF entry has no payload" }
                    System.arraycopy(payload, 0, metadata, entry.externalOffset.toInt(), payload.size)
                }
            }
            entryOffset += TIFF_ENTRY_BYTES.toInt()
        }
        putUInt32(metadata, entryOffset, 0L)

        // Stream CP3's immutable U16 signal directly. CP4 never materializes another full raster.
        require(fused.pixelCount.toLong() == pixels)
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = CountingOutputStream(DigestOutputStream(output, digest), maxOutputBytes)
        counted.write(metadata)
        writePadding(counted, privateOffset - counted.byteCount)
        counted.write(manifest)
        writePadding(counted, imageOffset - counted.byteCount)
        for (index in 0 until fused.pixelCount) {
            val sample = fused.signalDnAt(index)
            require(sample.isFinite() && sample >= 0f) { "CP4 fused sample is not finite non-negative sensor signal" }
            writeUInt16(counted, minOf(sample.toInt(), outputWhite.toInt()))
        }
        check(counted.byteCount == stripCursor) { "CP4 byte count diverged from deterministic TIFF layout" }
        counted.flush()

        return Cp4ComputationalDngReceipt(
            byteCount = counted.byteCount,
            sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) },
            cp3OutputSha256 = fused.outputSha256,
            calibrationFingerprintSha256 = calibration.report.calibrationFingerprintSha256,
            sourceCanonicalSha256 = fusionReport.sourceCanonicalSha256,
            includedOrdinals = fusionReport.includedOrdinals,
            outputWhiteLevelDn = outputWhite,
            stripCount = stripCount,
        )
    }

    private fun privateManifest(report: Cp3FusionReport, fused: Cp3FusedCfa): String = buildString {
        append("camx2-cp4-computational-dng-v2\n")
        append("encoding=uint16-black-subtracted-linear-cfa\n")
        append("cp3.algorithmId=").append(report.algorithmId).append('\n')
        append("cp3.algorithmVersion=").append(report.algorithmVersion).append('\n')
        append("cp3.outputSha256=").append(fused.outputSha256).append('\n')
        append("cp2.calibrationFingerprintSha256=").append(report.calibrationFingerprintSha256).append('\n')
        append("cfa=").append(fused.cfaPattern.name).append('\n')
        append("active=").append(fused.activeArea.left).append(',').append(fused.activeArea.top).append(',')
            .append(fused.activeArea.width).append(',').append(fused.activeArea.height).append('\n')
        append("includedOrdinals=").append(report.includedOrdinals.joinToString(",")).append('\n')
        append("sourceCanonicalSha256=").append(report.sourceCanonicalSha256.joinToString(",")).append('\n')
        append("multiFramePixelCount=").append(report.multiFramePixelCount).append('\n')
        append("referenceOnlyPixelCount=").append(report.referenceOnlyPixelCount).append('\n')
        append("censoredPixelCount=").append(report.censoredPixelCount).append('\n')
        append("rejectedPixelMeasurements=").append(report.rejectedPixelMeasurements).append('\n')
        append("fixedPatternNoiseMode=").append(report.fixedPatternNoiseMode.name).append('\n')
    }

    private fun cfaPattern(arrangement: Int?): CfaPattern? = when (arrangement) {
        0 -> CfaPattern.RGGB
        1 -> CfaPattern.GRBG
        2 -> CfaPattern.GBRG
        3 -> CfaPattern.BGGR
        else -> null
    }

    private fun cfaPatternBytes(pattern: CfaPattern, sourceLeft: Int, sourceTop: Int): ByteArray = ByteArray(4) { index ->
        val x = sourceLeft + (index and 1)
        val y = sourceTop + (index ushr 1)
        when (siteColor(pattern, x, y)) {
            SiteColor.RED -> CFA_RED
            SiteColor.GREEN -> CFA_GREEN
            SiteColor.BLUE -> CFA_BLUE
        }
    }

    private fun siteColor(pattern: CfaPattern, x: Int, y: Int): SiteColor {
        val site = ((y and 1) shl 1) or (x and 1)
        return when (pattern) {
            CfaPattern.RGGB -> when (site) {
                0 -> SiteColor.RED
                3 -> SiteColor.BLUE
                else -> SiteColor.GREEN
            }
            CfaPattern.GRBG -> when (site) {
                1 -> SiteColor.RED
                2 -> SiteColor.BLUE
                else -> SiteColor.GREEN
            }
            CfaPattern.GBRG -> when (site) {
                1 -> SiteColor.BLUE
                2 -> SiteColor.RED
                else -> SiteColor.GREEN
            }
            CfaPattern.BGGR -> when (site) {
                0 -> SiteColor.BLUE
                3 -> SiteColor.RED
                else -> SiteColor.GREEN
            }
        }
    }

    private fun matrixEntry(tag: Int, matrix: Cp2Matrix3x3Evidence): TiffEntry =
        TiffEntry(tag, TYPE_SRATIONAL, 9L, matrixBytes(matrix))

    private fun matrixBytes(matrix: Cp2Matrix3x3Evidence): ByteArray {
        val bytes = ByteArray(9 * 8)
        matrix.values.forEachIndexed { index, rational ->
            putInt32(bytes, index * 8, rational.numerator)
            putInt32(bytes, index * 8 + 4, rational.denominator)
        }
        return bytes
    }

    private fun asciiEntry(tag: Int, value: String): TiffEntry {
        val payload = value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        return TiffEntry(tag, TYPE_ASCII, payload.size.toLong(), payload)
    }

    private fun shortEntry(tag: Int, value: Int): TiffEntry {
        require(value in 0..0xffff)
        return TiffEntry(tag, TYPE_SHORT, 1L, shortArrayBytes(intArrayOf(value)))
    }

    private fun longEntry(tag: Int, value: Long): TiffEntry {
        require(value in 0..0xffff_ffffL)
        return TiffEntry(tag, TYPE_LONG, 1L, uint32ArrayBytes(longArrayOf(value)))
    }

    private data class TiffEntry(
        val tag: Int,
        val type: Int,
        val count: Long,
        val payload: ByteArray?,
        val externalKind: ExternalKind = ExternalKind.NORMAL,
        var externalOffset: Long = 0L,
    ) {
        fun byteCount(): Long {
            val bytesPerElement = when (type) {
                1, 2 -> 1L
                3 -> 2L
                4 -> 4L
                10 -> 8L
                else -> throw IllegalArgumentException("Unsupported CP4 TIFF type $type")
            }
            return try {
                Math.multiplyExact(bytesPerElement, count)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("CP4 TIFF entry size overflow", error)
            }
        }
    }

    private enum class ExternalKind { NORMAL, PRIVATE }
    private enum class SiteColor { RED, GREEN, BLUE }

    private class CountingOutputStream(output: OutputStream, private val maxBytes: Long) : FilterOutputStream(output) {
        var byteCount: Long = 0L
            private set

        override fun write(value: Int) {
            ensure(1L)
            out.write(value)
            byteCount++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset.toLong() + length.toLong() <= buffer.size.toLong())
            ensure(length.toLong())
            out.write(buffer, offset, length)
            byteCount = try {
                Math.addExact(byteCount, length.toLong())
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("CP4 output byte count overflow", error)
            }
        }

        private fun ensure(additional: Long) {
            val newCount = try {
                Math.addExact(byteCount, additional)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("CP4 output byte count overflow", error)
            }
            require(newCount <= maxBytes) { "CP4 output exceeded the admitted byte budget" }
        }
    }

    private fun writePadding(output: CountingOutputStream, count: Long) {
        require(count >= 0L && count <= MAX_METADATA_BYTES)
        repeat(count.toInt()) { output.write(0) }
    }

    private fun writeUInt16(output: OutputStream, value: Int) {
        require(value in 0..0xffff)
        output.write(value)
        output.write(value ushr 8)
    }

    private fun shortArrayBytes(values: IntArray): ByteArray = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { index, value -> putUInt16(bytes, index * 2, value) }
    }

    private fun uint32ArrayBytes(values: LongArray): ByteArray = ByteArray(values.size * 4).also { bytes ->
        values.forEachIndexed { index, value -> putUInt32(bytes, index * 4, value) }
    }

    private fun putUInt16(bytes: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff)
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xffff_ffffL)
        val intValue = value.toInt()
        bytes[offset] = intValue.toByte()
        bytes[offset + 1] = (intValue ushr 8).toByte()
        bytes[offset + 2] = (intValue ushr 16).toByte()
        bytes[offset + 3] = (intValue ushr 24).toByte()
    }

    private fun putInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun typeSize(type: Int): Int = when (type) {
        TYPE_BYTE, TYPE_ASCII -> 1
        TYPE_SHORT -> 2
        TYPE_LONG -> 4
        TYPE_SRATIONAL -> 8
        else -> throw IllegalArgumentException("Unsupported CP4 TIFF type $type")
    }

    private fun align4(value: Long): Long = checkedAdd(value, 3L, "CP4 alignment overflow") and 3L.inv()

    private fun checkedAdd(left: Long, right: Long, message: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException(message, error)
    }

    private fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException(message, error)
    }

    private companion object {
        const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
        const val MAX_IMAGE_PIXELS = 50_000_000L
        const val MAX_METADATA_BYTES = 1024L * 1024L
        const val MAX_PRIVATE_MANIFEST_BYTES = 256 * 1024
        const val MAX_UNIQUE_CAMERA_MODEL_BYTES = 255
        const val MAX_IFD_ENTRIES = 64
        const val MAX_STRIPS = 65_536
        const val ROWS_PER_STRIP = 64
        const val UINT16_BYTES = 2L
        const val TIFF_HEADER_BYTES = 8L
        const val TIFF_ENTRY_BYTES = 12L
        const val TIFF_MAGIC = 42

        const val TYPE_BYTE = 1
        const val TYPE_ASCII = 2
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_SRATIONAL = 10

        const val COMPRESSION_NONE = 1
        const val PHOTOMETRIC_CFA = 32803
        const val PLANAR_CHUNKY = 1
        const val SAMPLE_FORMAT_UNSIGNED_INT = 1
        const val CFA_LAYOUT_RECTANGULAR = 1
        const val CFA_RED: Byte = 0
        const val CFA_GREEN: Byte = 1
        const val CFA_BLUE: Byte = 2

        const val TAG_IMAGE_WIDTH = 256
        const val TAG_IMAGE_LENGTH = 257
        const val TAG_BITS_PER_SAMPLE = 258
        const val TAG_COMPRESSION = 259
        const val TAG_PHOTOMETRIC_INTERPRETATION = 262
        const val TAG_STRIP_OFFSETS = 273
        const val TAG_ORIENTATION = 274
        const val TAG_SAMPLES_PER_PIXEL = 277
        const val TAG_ROWS_PER_STRIP = 278
        const val TAG_STRIP_BYTE_COUNTS = 279
        const val TAG_PLANAR_CONFIGURATION = 284
        const val TAG_SOFTWARE = 305
        const val TAG_SAMPLE_FORMAT = 339
        const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
        const val TAG_CFA_PATTERN = 33422
        const val TAG_DNG_VERSION = 50706
        const val TAG_DNG_BACKWARD_VERSION = 50707
        const val TAG_UNIQUE_CAMERA_MODEL = 50708
        const val TAG_CFA_PLANE_COLOR = 50710
        const val TAG_CFA_LAYOUT = 50711
        const val TAG_BLACK_LEVEL = 50714
        const val TAG_WHITE_LEVEL = 50717
        const val TAG_COLOR_MATRIX_1 = 50721
        const val TAG_COLOR_MATRIX_2 = 50722
        const val TAG_CAMERA_CALIBRATION_1 = 50723
        const val TAG_CAMERA_CALIBRATION_2 = 50724
        const val TAG_DNG_PRIVATE_DATA = 50740
        const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
        const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
        const val TAG_ACTIVE_AREA = 50829
        const val TAG_FORWARD_MATRIX_1 = 50964
        const val TAG_FORWARD_MATRIX_2 = 50965
    }
}

class Cp4ComputationalDngReceipt(
    val byteCount: Long,
    val sha256: String,
    val cp3OutputSha256: String,
    val calibrationFingerprintSha256: String,
    sourceCanonicalSha256: List<String>,
    includedOrdinals: List<Int>,
    val outputWhiteLevelDn: Long,
    val stripCount: Int,
) {
    val sourceCanonicalSha256: List<String> = sourceCanonicalSha256.toList()
    val includedOrdinals: List<Int> = includedOrdinals.toList()

    init {
        require(byteCount > 0L)
        listOf(sha256, cp3OutputSha256, calibrationFingerprintSha256).forEach { digest ->
            require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' })
        }
        require(this.sourceCanonicalSha256.isNotEmpty() && this.sourceCanonicalSha256.all { it.length == 64 })
        require(this.includedOrdinals.size >= 2 && this.includedOrdinals.distinct().size == this.includedOrdinals.size)
        require(outputWhiteLevelDn > 0L && stripCount > 0)
    }
}
