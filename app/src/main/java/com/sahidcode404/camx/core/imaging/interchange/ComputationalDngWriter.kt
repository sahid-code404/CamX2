package com.sahidcode404.camx.core.imaging.interchange

import com.sahidcode404.camx.core.imaging.calibration.CfaSiteColor
import com.sahidcode404.camx.core.imaging.reconstruction.FusedCfaRadiance
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

// This product includes DNG technology under license by Adobe.
class ComputationalDngWriter {
    fun writeCfa(
        negative: FusedCfaRadiance,
        authority: ComputationalCfaDngAuthority,
        output: OutputStream,
        maxOutputBytes: Long,
    ): ComputationalDngReceipt {
        val privateManifest = ComputationalDngManifest.canonical(negative, authority).toByteArray(Charsets.UTF_8)
        val reservation = ComputationalDngReservation.forNegative(
            negative = negative,
            authority = authority,
            privateManifestBytes = privateManifest.size,
            maxOutputBytes = maxOutputBytes,
        )
        val layout = buildLayout(negative, authority, reservation)
        require(layout.fileByteCount <= reservation.maxOutputBytes) {
            "M8B exact DNG layout exceeds the admitted output budget"
        }
        require(layout.fileByteCount <= M8BComputationalDngLimits.MAX_FILE_BYTES) {
            "M8B exact DNG layout exceeds the reference writer file bound"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val counter = CountingOutputStream(DigestOutputStream(output, digest), reservation.maxOutputBytes)
        counter.write(layout.metadata)
        writePadding(counter, layout.privateOffset - counter.byteCount)
        writePrivateData(counter, negative, privateManifest)
        writePadding(counter, layout.imageOffset - counter.byteCount)
        writeFloatRaster(counter, negative, reservation.outputWhiteLevelDn)
        check(counter.byteCount == layout.fileByteCount) {
            "M8B deterministic DNG writer byte count diverged from the admitted layout"
        }
        counter.flush()

        return ComputationalDngReceipt(
            byteCount = counter.byteCount,
            sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) },
            sourceOutputSha256 = negative.provenance.outputSha256,
            sourceManifestSha256 = negative.provenance.manifestSha256,
            privateManifestSha256 = sha256(privateManifest),
            stripCount = reservation.stripCount,
            outputWhiteLevelDn = reservation.outputWhiteLevelDn,
        )
    }

    private fun buildLayout(
        negative: FusedCfaRadiance,
        authority: ComputationalCfaDngAuthority,
        reservation: ComputationalDngReservation,
    ): DngLayout {
        val profile = authority.calibrationProfile
        val colorEntries = profile.colorCalibration?.entries
            ?: throw IllegalArgumentException("M8B CFA DNG cannot fabricate missing color calibration")
        require(colorEntries.size in 1..2)

        val stripByteCounts = LongArray(reservation.stripCount) { strip ->
            val firstRow = strip * reservation.rowsPerStrip
            val rowCount = minOf(reservation.rowsPerStrip, reservation.height - firstRow)
            checkedMultiply(
                checkedMultiply(rowCount.toLong(), reservation.width.toLong(), "M8B strip pixel count overflow"),
                M8BComputationalDngLimits.FLOAT_SAMPLE_BYTES,
                "M8B strip byte count overflow",
            )
        }
        check(stripByteCounts.sum() == reservation.imageBytes)

        val stripOffsetsPayload = ByteArray(reservation.stripCount * 4)
        val stripByteCountsPayload = uint32ArrayBytes(stripByteCounts)
        val cfaPattern = exportCfaPattern(negative, authority)
        val entries = ArrayList<TiffEntryDef>()
        entries += scalarLong(TAG_IMAGE_WIDTH, reservation.width.toLong())
        entries += scalarLong(TAG_IMAGE_LENGTH, reservation.height.toLong())
        entries += scalarShort(TAG_BITS_PER_SAMPLE, 32)
        entries += scalarShort(TAG_COMPRESSION, COMPRESSION_NONE)
        entries += scalarShort(TAG_PHOTOMETRIC_INTERPRETATION, PHOTOMETRIC_CFA)
        entries += TiffEntryDef(TAG_STRIP_OFFSETS, TYPE_LONG, reservation.stripCount.toLong(), stripOffsetsPayload)
        entries += scalarShort(TAG_ORIENTATION, ORIENTATION_TOP_LEFT)
        entries += scalarShort(TAG_SAMPLES_PER_PIXEL, 1)
        entries += scalarLong(TAG_ROWS_PER_STRIP, reservation.rowsPerStrip.toLong())
        entries += TiffEntryDef(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, reservation.stripCount.toLong(), stripByteCountsPayload)
        entries += scalarShort(TAG_PLANAR_CONFIGURATION, PLANAR_CHUNKY)
        entries += scalarShort(TAG_SAMPLE_FORMAT, SAMPLE_FORMAT_IEEE_FLOAT)
        entries += TiffEntryDef(TAG_CFA_REPEAT_PATTERN_DIM, TYPE_SHORT, 2L, shortArrayBytes(intArrayOf(2, 2)))
        entries += TiffEntryDef(TAG_CFA_PATTERN, TYPE_BYTE, 4L, cfaPattern)
        entries += TiffEntryDef(TAG_DNG_VERSION, TYPE_BYTE, 4L, byteArrayOf(1, 4, 0, 0))
        entries += TiffEntryDef(TAG_DNG_BACKWARD_VERSION, TYPE_BYTE, 4L, byteArrayOf(1, 4, 0, 0))
        val modelBytes = authority.uniqueCameraModel.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        entries += TiffEntryDef(TAG_UNIQUE_CAMERA_MODEL, TYPE_ASCII, modelBytes.size.toLong(), modelBytes)
        entries += TiffEntryDef(TAG_CFA_PLANE_COLOR, TYPE_BYTE, 3L, byteArrayOf(CFA_RED, CFA_GREEN, CFA_BLUE))
        entries += scalarShort(TAG_CFA_LAYOUT, CFA_LAYOUT_RECTANGULAR)
        entries += scalarLong(TAG_BLACK_LEVEL, 0L)
        entries += scalarLong(TAG_WHITE_LEVEL, reservation.outputWhiteLevelDn)
        entries += TiffEntryDef(
            TAG_COLOR_MATRIX_1,
            TYPE_SRATIONAL,
            9L,
            srationalMatrixBytes(invert3x3(colorEntries[0].sensorToXyz.values().toDoubleArray())),
        )
        entries += TiffEntryDef(
            TAG_DNG_PRIVATE_DATA,
            TYPE_BYTE,
            reservation.privateDataBytes,
            payload = null,
            externalKind = ExternalKind.PRIVATE_DATA,
        )
        entries += scalarShort(TAG_CALIBRATION_ILLUMINANT_1, colorEntries[0].illuminant.code)
        entries += TiffEntryDef(
            TAG_ACTIVE_AREA,
            TYPE_LONG,
            4L,
            uint32ArrayBytes(longArrayOf(0L, 0L, reservation.height.toLong(), reservation.width.toLong())),
        )
        if (colorEntries.size == 2) {
            entries += TiffEntryDef(
                TAG_COLOR_MATRIX_2,
                TYPE_SRATIONAL,
                9L,
                srationalMatrixBytes(invert3x3(colorEntries[1].sensorToXyz.values().toDoubleArray())),
            )
            entries += scalarShort(TAG_CALIBRATION_ILLUMINANT_2, colorEntries[1].illuminant.code)
        }
        entries.sortBy { it.tag }
        require(entries.size <= M8BComputationalDngLimits.MAX_IFD_ENTRIES)
        require(entries.map { it.tag }.distinct().size == entries.size)

        val ifdSize = 2L + entries.size.toLong() * TIFF_ENTRY_BYTES + 4L
        var extraCursor = align4(TIFF_HEADER_BYTES + ifdSize)
        entries.forEach { entry ->
            val size = entry.byteCount()
            require(size <= 0xffff_ffffL) { "M8B TIFF entry byte count exceeds classic TIFF" }
            if (size > 4L && entry.externalKind == ExternalKind.NORMAL) {
                entry.externalOffset = extraCursor
                extraCursor = align4(checkedAdd(extraCursor, size, "M8B TIFF metadata offset overflow"))
            }
        }
        val privateOffset = align4(extraCursor)
        require(privateOffset <= M8BComputationalDngLimits.MAX_METADATA_RESERVATION_BYTES) {
            "M8B TIFF metadata exceeds the admitted metadata reservation"
        }
        val imageOffset = align4(
            checkedAdd(privateOffset, reservation.privateDataBytes, "M8B image offset overflow"),
        )
        var stripCursor = imageOffset
        val stripOffsets = LongArray(reservation.stripCount)
        stripByteCounts.forEachIndexed { index, count ->
            stripOffsets[index] = stripCursor
            stripCursor = checkedAdd(stripCursor, count, "M8B strip offset overflow")
        }
        require(stripCursor <= 0xffff_ffffL) { "M8B classic TIFF reference cannot exceed 32-bit offsets" }
        val stripOffsetBytes = uint32ArrayBytes(stripOffsets)
        System.arraycopy(stripOffsetBytes, 0, stripOffsetsPayload, 0, stripOffsetBytes.size)

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
            val byteCount = entry.byteCount()
            if (byteCount <= 4L) {
                val payload = entry.payload ?: error("M8B inline TIFF value has no payload")
                System.arraycopy(payload, 0, metadata, entryOffset + 8, payload.size)
            } else {
                putUInt32(metadata, entryOffset + 8, entry.externalOffset)
                if (entry.externalKind == ExternalKind.NORMAL) {
                    val payload = entry.payload ?: error("M8B external TIFF value has no payload")
                    System.arraycopy(payload, 0, metadata, entry.externalOffset.toInt(), payload.size)
                }
            }
            entryOffset += TIFF_ENTRY_BYTES.toInt()
        }
        putUInt32(metadata, entryOffset, 0L)

        return DngLayout(
            metadata = metadata,
            privateOffset = privateOffset,
            imageOffset = imageOffset,
            fileByteCount = stripCursor,
        )
    }

    private fun writePrivateData(
        output: CountingOutputStream,
        negative: FusedCfaRadiance,
        manifest: ByteArray,
    ) {
        output.write(PRIVATE_IDENTIFIER)
        output.write(PRIVATE_MAGIC)
        writeUInt16(output, PRIVATE_VERSION)
        writeUInt32(output, manifest.size.toLong())
        writeUInt32(output, negative.width.toLong() * negative.height.toLong())
        output.write(manifest)
        for (y in 0 until negative.height) {
            for (x in 0 until negative.width) {
                val sample = negative.sampleAt(negative.activeArea.left + x, negative.activeArea.top + y)
                writeFloat32(output, sample.varianceDn2.toFloat())
                writeFloat32(output, sample.effectiveSampleCount.toFloat())
                require(sample.contributingFrames in 0..255)
                output.write(sample.contributingFrames)
                var flags = 0
                if (sample.lowCensored) flags = flags or PRIVATE_FLAG_LOW_CENSORED
                if (sample.highCensored) flags = flags or PRIVATE_FLAG_HIGH_CENSORED
                if (sample.referenceOnly) flags = flags or PRIVATE_FLAG_REFERENCE_ONLY
                if (sample.measurementValid) flags = flags or PRIVATE_FLAG_MEASUREMENT_VALID
                output.write(flags)
            }
        }
    }

    private fun writeFloatRaster(
        output: CountingOutputStream,
        negative: FusedCfaRadiance,
        outputWhiteLevelDn: Long,
    ) {
        val white = outputWhiteLevelDn.toDouble()
        for (y in 0 until negative.height) {
            for (x in 0 until negative.width) {
                val sample = negative.sampleAt(negative.activeArea.left + x, negative.activeArea.top + y)
                val encoded = minOf(sample.radianceDn, white).toFloat()
                require(encoded.isFinite() && encoded >= 0f) { "M8B encoded CFA sample must remain finite and non-negative" }
                writeFloat32(output, encoded)
            }
        }
    }

    private fun exportCfaPattern(
        negative: FusedCfaRadiance,
        authority: ComputationalCfaDngAuthority,
    ): ByteArray {
        val profile = authority.calibrationProfile
        return ByteArray(4) { index ->
            val x = index and 1
            val y = index ushr 1
            when (profile.siteColorAt(negative.activeArea.left + x, negative.activeArea.top + y)) {
                CfaSiteColor.RED -> CFA_RED
                CfaSiteColor.GREEN -> CFA_GREEN
                CfaSiteColor.BLUE -> CFA_BLUE
            }
        }
    }

    private fun invert3x3(matrix: DoubleArray): DoubleArray {
        require(matrix.size == 9)
        val a = matrix[0]; val b = matrix[1]; val c = matrix[2]
        val d = matrix[3]; val e = matrix[4]; val f = matrix[5]
        val g = matrix[6]; val h = matrix[7]; val i = matrix[8]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(determinant.isFinite() && kotlin.math.abs(determinant) > 1e-12) {
            "M8B color matrix inversion requires finite non-singular calibration"
        }
        val inverse = doubleArrayOf(
            (e * i - f * h) / determinant,
            (c * h - b * i) / determinant,
            (b * f - c * e) / determinant,
            (f * g - d * i) / determinant,
            (a * i - c * g) / determinant,
            (c * d - a * f) / determinant,
            (d * h - e * g) / determinant,
            (b * g - a * h) / determinant,
            (a * e - b * d) / determinant,
        )
        require(inverse.all { it.isFinite() })
        return inverse
    }

    private fun srationalMatrixBytes(values: DoubleArray): ByteArray {
        val bytes = ByteArray(values.size * 8)
        values.forEachIndexed { index, value ->
            val denominator = M8BComputationalDngLimits.MAX_SRATIONAL_DENOMINATOR
            val scaled = Math.round(value * denominator.toDouble())
            require(scaled in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "M8B color matrix value cannot be represented as bounded DNG SRATIONAL"
            }
            putInt32(bytes, index * 8, scaled.toInt())
            putInt32(bytes, index * 8 + 4, denominator)
        }
        return bytes
    }

    private fun scalarShort(tag: Int, value: Int): TiffEntryDef {
        require(value in 0..0xffff)
        return TiffEntryDef(tag, TYPE_SHORT, 1L, shortArrayBytes(intArrayOf(value)))
    }

    private fun scalarLong(tag: Int, value: Long): TiffEntryDef {
        require(value in 0..0xffff_ffffL)
        return TiffEntryDef(tag, TYPE_LONG, 1L, uint32ArrayBytes(longArrayOf(value)))
    }

    private data class DngLayout(
        val metadata: ByteArray,
        val privateOffset: Long,
        val imageOffset: Long,
        val fileByteCount: Long,
    )

    private enum class ExternalKind { NORMAL, PRIVATE_DATA }

    private data class TiffEntryDef(
        val tag: Int,
        val type: Int,
        val count: Long,
        val payload: ByteArray?,
        val externalKind: ExternalKind = ExternalKind.NORMAL,
        var externalOffset: Long = 0L,
    ) {
        fun byteCount(): Long = checkedMultiply(typeSize(type).toLong(), count, "M8B TIFF entry size overflow")
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long,
    ) : OutputStream() {
        var byteCount: Long = 0L
            private set

        override fun write(value: Int) {
            ensureCapacity(1L)
            delegate.write(value)
            byteCount++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset.toLong() + length.toLong() <= bytes.size.toLong())
            ensureCapacity(length.toLong())
            delegate.write(bytes, offset, length)
            byteCount += length.toLong()
        }

        override fun flush() = delegate.flush()

        private fun ensureCapacity(increment: Long) {
            val next = checkedAdd(byteCount, increment, "M8B streaming output byte counter overflow")
            require(next <= maxBytes) { "M8B streaming writer exceeded its admitted output budget" }
        }
    }

    companion object {
        private const val TIFF_HEADER_BYTES = 8L
        private const val TIFF_ENTRY_BYTES = 12L
        private const val TIFF_MAGIC = 42

        private const val TYPE_BYTE = 1
        private const val TYPE_ASCII = 2
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_SRATIONAL = 10

        private const val TAG_IMAGE_WIDTH = 256
        private const val TAG_IMAGE_LENGTH = 257
        private const val TAG_BITS_PER_SAMPLE = 258
        private const val TAG_COMPRESSION = 259
        private const val TAG_PHOTOMETRIC_INTERPRETATION = 262
        private const val TAG_STRIP_OFFSETS = 273
        private const val TAG_ORIENTATION = 274
        private const val TAG_SAMPLES_PER_PIXEL = 277
        private const val TAG_ROWS_PER_STRIP = 278
        private const val TAG_STRIP_BYTE_COUNTS = 279
        private const val TAG_PLANAR_CONFIGURATION = 284
        private const val TAG_SAMPLE_FORMAT = 339
        private const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
        private const val TAG_CFA_PATTERN = 33422
        private const val TAG_DNG_VERSION = 50706
        private const val TAG_DNG_BACKWARD_VERSION = 50707
        private const val TAG_UNIQUE_CAMERA_MODEL = 50708
        private const val TAG_CFA_PLANE_COLOR = 50710
        private const val TAG_CFA_LAYOUT = 50711
        private const val TAG_BLACK_LEVEL = 50714
        private const val TAG_WHITE_LEVEL = 50717
        private const val TAG_COLOR_MATRIX_1 = 50721
        private const val TAG_COLOR_MATRIX_2 = 50722
        private const val TAG_DNG_PRIVATE_DATA = 50740
        private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
        private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
        private const val TAG_ACTIVE_AREA = 50829

        private const val COMPRESSION_NONE = 1
        private const val PHOTOMETRIC_CFA = 32803
        private const val ORIENTATION_TOP_LEFT = 1
        private const val PLANAR_CHUNKY = 1
        private const val SAMPLE_FORMAT_IEEE_FLOAT = 3
        private const val CFA_LAYOUT_RECTANGULAR = 1
        private const val CFA_RED: Byte = 0
        private const val CFA_GREEN: Byte = 1
        private const val CFA_BLUE: Byte = 2

        private val PRIVATE_IDENTIFIER = byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte(), 0)
        private val PRIVATE_MAGIC = byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte())
        private const val PRIVATE_VERSION = 1
        private const val PRIVATE_FLAG_LOW_CENSORED = 1
        private const val PRIVATE_FLAG_HIGH_CENSORED = 1 shl 1
        private const val PRIVATE_FLAG_REFERENCE_ONLY = 1 shl 2
        private const val PRIVATE_FLAG_MEASUREMENT_VALID = 1 shl 3

        private fun typeSize(type: Int): Int = when (type) {
            TYPE_BYTE, TYPE_ASCII -> 1
            TYPE_SHORT -> 2
            TYPE_LONG -> 4
            TYPE_SRATIONAL -> 8
            else -> throw IllegalArgumentException("M8B unsupported TIFF type $type")
        }

        private fun align4(value: Long): Long = checkedAdd(value, 3L, "M8B TIFF alignment overflow") and -4L

        private fun shortArrayBytes(values: IntArray): ByteArray = ByteArray(values.size * 2).also { bytes ->
            values.forEachIndexed { index, value ->
                require(value in 0..0xffff)
                putUInt16(bytes, index * 2, value)
            }
        }

        private fun uint32ArrayBytes(values: LongArray): ByteArray = ByteArray(values.size * 4).also { bytes ->
            values.forEachIndexed { index, value -> putUInt32(bytes, index * 4, value) }
        }

        private fun putUInt16(bytes: ByteArray, offset: Int, value: Int) {
            require(value in 0..0xffff)
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }

        private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
            require(value in 0..0xffff_ffffL)
            bytes[offset] = (value and 0xffL).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xffL).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xffL).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xffL).toByte()
        }

        private fun putInt32(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }

        private fun writeUInt16(output: OutputStream, value: Int) {
            require(value in 0..0xffff)
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }

        private fun writeUInt32(output: OutputStream, value: Long) {
            require(value in 0..0xffff_ffffL)
            output.write((value and 0xffL).toInt())
            output.write(((value ushr 8) and 0xffL).toInt())
            output.write(((value ushr 16) and 0xffL).toInt())
            output.write(((value ushr 24) and 0xffL).toInt())
        }

        private fun writeFloat32(output: OutputStream, value: Float) {
            require(value.isFinite())
            val bits = java.lang.Float.floatToRawIntBits(value)
            output.write(bits and 0xff)
            output.write((bits ushr 8) and 0xff)
            output.write((bits ushr 16) and 0xff)
            output.write((bits ushr 24) and 0xff)
        }

        private fun writePadding(output: OutputStream, count: Long) {
            require(count >= 0L)
            repeat(count.toInt()) { output.write(0) }
        }
    }
}
