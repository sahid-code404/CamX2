package com.sahidcode404.camx.core.imaging.interchange

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

class ParsedComputationalDng internal constructor(
    val width: Int,
    val height: Int,
    val uniqueCameraModel: String,
    cfaPattern: List<Int>,
    val outputWhiteLevelDn: Long,
    val calibrationIlluminant1: Int,
    colorMatrix1: List<Double>,
    val calibrationIlluminant2: Int?,
    colorMatrix2: List<Double>?,
    val privateManifest: String,
    radianceDn: FloatArray,
    varianceDn2: FloatArray,
    effectiveSampleCount: FloatArray,
    contributors: ByteArray,
    flags: ByteArray,
) {
    val cfaPattern: List<Int> = Collections.unmodifiableList(ArrayList(cfaPattern))
    val colorMatrix1: List<Double> = Collections.unmodifiableList(ArrayList(colorMatrix1))
    val colorMatrix2: List<Double>? = colorMatrix2?.let { Collections.unmodifiableList(ArrayList(it)) }
    private val radianceDn = radianceDn.copyOf()
    private val varianceDn2 = varianceDn2.copyOf()
    private val effectiveSampleCount = effectiveSampleCount.copyOf()
    private val contributors = contributors.copyOf()
    private val flags = flags.copyOf()

    init {
        val expected = width.toLong() * height.toLong()
        require(expected <= Int.MAX_VALUE.toLong())
        val count = expected.toInt()
        require(this.radianceDn.size == count)
        require(this.varianceDn2.size == count)
        require(this.effectiveSampleCount.size == count)
        require(this.contributors.size == count)
        require(this.flags.size == count)
    }

    fun radianceAt(x: Int, y: Int): Double = radianceDn[indexOf(x, y)].toDouble()

    fun uncertaintyAt(x: Int, y: Int): ParsedDngUncertaintySample {
        val index = indexOf(x, y)
        val flag = flags[index].toInt() and 0xff
        return ParsedDngUncertaintySample(
            varianceDn2 = varianceDn2[index].toDouble(),
            effectiveSampleCount = effectiveSampleCount[index].toDouble(),
            contributingFrames = contributors[index].toInt() and 0xff,
            lowCensored = flag and FLAG_LOW_CENSORED != 0,
            highCensored = flag and FLAG_HIGH_CENSORED != 0,
            referenceOnly = flag and FLAG_REFERENCE_ONLY != 0,
            measurementValid = flag and FLAG_MEASUREMENT_VALID != 0,
        )
    }

    private fun indexOf(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return y * width + x
    }

    private companion object {
        const val FLAG_LOW_CENSORED = 1
        const val FLAG_HIGH_CENSORED = 1 shl 1
        const val FLAG_REFERENCE_ONLY = 1 shl 2
        const val FLAG_MEASUREMENT_VALID = 1 shl 3
    }
}

data class ParsedDngUncertaintySample(
    val varianceDn2: Double,
    val effectiveSampleCount: Double,
    val contributingFrames: Int,
    val lowCensored: Boolean,
    val highCensored: Boolean,
    val referenceOnly: Boolean,
    val measurementValid: Boolean,
)

object ComputationalDngInspector {
    fun inspect(bytes: ByteArray): ParsedComputationalDng {
        require(bytes.size >= TIFF_HEADER_BYTES && bytes.size.toLong() <= M8BComputationalDngLimits.MAX_FILE_BYTES) {
            "M8B DNG inspector input extent is outside the bounded reference parser"
        }
        require(bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            "M8B reference parser accepts only little-endian TIFF"
        }
        require(readUInt16(bytes, 2) == TIFF_MAGIC) { "M8B invalid TIFF magic" }
        val ifdOffset = readUInt32(bytes, 4)
        require(ifdOffset == TIFF_HEADER_BYTES.toLong()) { "M8B reference parser requires the canonical first-IFD layout" }
        val entryCount = readUInt16(bytes, ifdOffset.toInt())
        require(entryCount in 1..M8BComputationalDngLimits.MAX_IFD_ENTRIES) { "M8B IFD entry count is not bounded" }
        val ifdBytes = checkedAdd(2L, checkedAdd(entryCount.toLong() * TIFF_ENTRY_BYTES, 4L, "M8B IFD size overflow"), "M8B IFD size overflow")
        requireRange(bytes, ifdOffset, ifdBytes, "M8B IFD extent")

        val entries = LinkedHashMap<Int, ParsedEntry>()
        var cursor = ifdOffset.toInt() + 2
        repeat(entryCount) {
            val tag = readUInt16(bytes, cursor)
            val type = readUInt16(bytes, cursor + 2)
            val count = readUInt32(bytes, cursor + 4)
            require(count > 0L) { "M8B TIFF entry count must be positive" }
            require(!entries.containsKey(tag)) { "M8B duplicate TIFF tag $tag" }
            val byteCount = checkedMultiply(typeSize(type).toLong(), count, "M8B TIFF entry byte count overflow")
            require(byteCount <= M8BComputationalDngLimits.MAX_FILE_BYTES)
            val dataOffset = if (byteCount <= 4L) {
                (cursor + 8).toLong()
            } else {
                readUInt32(bytes, cursor + 8)
            }
            requireRange(bytes, dataOffset, byteCount, "M8B TIFF tag $tag payload")
            entries[tag] = ParsedEntry(tag, type, count, dataOffset, byteCount)
            cursor += TIFF_ENTRY_BYTES.toInt()
        }
        require(readUInt32(bytes, cursor) == 0L) { "M8B reference DNG must not chain another IFD" }

        val width = scalarLongOrShort(bytes, required(entries, TAG_IMAGE_WIDTH)).toIntExact("M8B image width")
        val height = scalarLongOrShort(bytes, required(entries, TAG_IMAGE_LENGTH)).toIntExact("M8B image height")
        require(width > 0 && height > 0)
        val pixelCount = checkedMultiply(width.toLong(), height.toLong(), "M8B parsed pixel count overflow")
        require(pixelCount in 1..M8BComputationalDngLimits.MAX_IMAGE_PIXELS)
        require(pixelCount <= Int.MAX_VALUE.toLong()) { "M8B parser arrays require JVM-addressable pixel count" }

        require(scalarLongOrShort(bytes, required(entries, TAG_BITS_PER_SAMPLE)) == 32L)
        require(scalarLongOrShort(bytes, required(entries, TAG_COMPRESSION)) == COMPRESSION_NONE.toLong())
        require(scalarLongOrShort(bytes, required(entries, TAG_PHOTOMETRIC_INTERPRETATION)) == PHOTOMETRIC_CFA.toLong())
        require(scalarLongOrShort(bytes, required(entries, TAG_ORIENTATION)) == ORIENTATION_TOP_LEFT.toLong())
        require(scalarLongOrShort(bytes, required(entries, TAG_SAMPLES_PER_PIXEL)) == 1L)
        require(scalarLongOrShort(bytes, required(entries, TAG_PLANAR_CONFIGURATION)) == PLANAR_CHUNKY.toLong())
        require(scalarLongOrShort(bytes, required(entries, TAG_SAMPLE_FORMAT)) == SAMPLE_FORMAT_IEEE_FLOAT.toLong())
        require(byteValues(bytes, required(entries, TAG_DNG_VERSION)) == listOf(1, 4, 0, 0))
        require(byteValues(bytes, required(entries, TAG_DNG_BACKWARD_VERSION)) == listOf(1, 4, 0, 0))
        require(shortValues(bytes, required(entries, TAG_CFA_REPEAT_PATTERN_DIM)) == listOf(2, 2))
        val cfaPattern = byteValues(bytes, required(entries, TAG_CFA_PATTERN))
        require(cfaPattern.size == 4 && cfaPattern.all { it in CFA_RED..CFA_BLUE })
        require(byteValues(bytes, required(entries, TAG_CFA_PLANE_COLOR)) == listOf(CFA_RED, CFA_GREEN, CFA_BLUE))
        require(scalarLongOrShort(bytes, required(entries, TAG_CFA_LAYOUT)) == CFA_LAYOUT_RECTANGULAR.toLong())
        require(scalarLongOrShort(bytes, required(entries, TAG_BLACK_LEVEL)) == 0L)
        val whiteLevel = scalarLongOrShort(bytes, required(entries, TAG_WHITE_LEVEL))
        require(whiteLevel > 0L)
        val activeArea = longOrShortValues(bytes, required(entries, TAG_ACTIVE_AREA))
        require(activeArea == listOf(0L, 0L, height.toLong(), width.toLong()))

        val uniqueModel = asciiValue(bytes, required(entries, TAG_UNIQUE_CAMERA_MODEL))
        require(uniqueModel.isNotBlank() && uniqueModel.toByteArray(Charsets.UTF_8).size <= M8BComputationalDngLimits.MAX_UNIQUE_CAMERA_MODEL_BYTES)
        val illuminant1 = scalarLongOrShort(bytes, required(entries, TAG_CALIBRATION_ILLUMINANT_1)).toIntExact("M8B illuminant 1")
        val colorMatrix1 = srationalValues(bytes, required(entries, TAG_COLOR_MATRIX_1))
        require(colorMatrix1.size == 9)
        val matrix2Entry = entries[TAG_COLOR_MATRIX_2]
        val illuminant2Entry = entries[TAG_CALIBRATION_ILLUMINANT_2]
        require((matrix2Entry == null) == (illuminant2Entry == null)) {
            "M8B second DNG color matrix and illuminant must appear together"
        }
        val colorMatrix2 = matrix2Entry?.let { srationalValues(bytes, it).also { values -> require(values.size == 9) } }
        val illuminant2 = illuminant2Entry?.let { scalarLongOrShort(bytes, it).toIntExact("M8B illuminant 2") }

        val rowsPerStrip = scalarLongOrShort(bytes, required(entries, TAG_ROWS_PER_STRIP)).toIntExact("M8B RowsPerStrip")
        require(rowsPerStrip > 0)
        val stripOffsets = longValues(bytes, required(entries, TAG_STRIP_OFFSETS))
        val stripCounts = longValues(bytes, required(entries, TAG_STRIP_BYTE_COUNTS))
        require(stripOffsets.isNotEmpty() && stripOffsets.size == stripCounts.size)
        require(stripOffsets.size <= M8BComputationalDngLimits.MAX_STRIPS)
        val expectedStripCount = ((height.toLong() + rowsPerStrip - 1L) / rowsPerStrip).toInt()
        require(stripOffsets.size == expectedStripCount)

        val privateEntry = required(entries, TAG_DNG_PRIVATE_DATA)
        require(privateEntry.type == TYPE_BYTE)
        require(privateEntry.byteCount <= M8BComputationalDngLimits.MAX_PRIVATE_DATA_BYTES)
        val privateEnd = checkedAdd(privateEntry.dataOffset, privateEntry.byteCount, "M8B private-data extent overflow")
        val parsedPrivate = parsePrivateData(bytes, privateEntry, pixelCount.toInt())

        val expectedImageBytes = checkedMultiply(pixelCount, 4L, "M8B parsed float raster extent overflow")
        var accumulatedImageBytes = 0L
        var expectedOffset: Long? = null
        stripCounts.forEachIndexed { index, count ->
            val firstRow = index * rowsPerStrip
            val rows = minOf(rowsPerStrip, height - firstRow)
            val expectedCount = rows.toLong() * width.toLong() * 4L
            require(count == expectedCount) { "M8B strip byte count does not match the declared raster" }
            require(stripOffsets[index] >= privateEnd) { "M8B image strip overlaps metadata/private evidence" }
            if (expectedOffset != null) require(stripOffsets[index] == expectedOffset) { "M8B reference strips must be contiguous" }
            requireRange(bytes, stripOffsets[index], count, "M8B image strip $index")
            expectedOffset = stripOffsets[index] + count
            accumulatedImageBytes = checkedAdd(accumulatedImageBytes, count, "M8B image byte accumulation overflow")
        }
        require(accumulatedImageBytes == expectedImageBytes)

        val radiance = FloatArray(pixelCount.toInt())
        var pixelIndex = 0
        stripOffsets.indices.forEach { strip ->
            var offset = stripOffsets[strip]
            val end = offset + stripCounts[strip]
            while (offset < end) {
                val value = readFloat32(bytes, offset.toInt())
                require(value.isFinite() && value >= 0f && value.toDouble() <= whiteLevel.toDouble()) {
                    "M8B parsed computational CFA sample violates the output-derived range"
                }
                radiance[pixelIndex++] = value
                offset += 4L
            }
        }
        check(pixelIndex == radiance.size)

        return ParsedComputationalDng(
            width = width,
            height = height,
            uniqueCameraModel = uniqueModel,
            cfaPattern = cfaPattern,
            outputWhiteLevelDn = whiteLevel,
            calibrationIlluminant1 = illuminant1,
            colorMatrix1 = colorMatrix1,
            calibrationIlluminant2 = illuminant2,
            colorMatrix2 = colorMatrix2,
            privateManifest = parsedPrivate.manifest,
            radianceDn = radiance,
            varianceDn2 = parsedPrivate.variance,
            effectiveSampleCount = parsedPrivate.effective,
            contributors = parsedPrivate.contributors,
            flags = parsedPrivate.flags,
        )
    }

    private fun parsePrivateData(bytes: ByteArray, entry: ParsedEntry, pixelCount: Int): ParsedPrivate {
        var cursor = entry.dataOffset
        require(readBytes(bytes, cursor, PRIVATE_IDENTIFIER.size.toLong()).contentEquals(PRIVATE_IDENTIFIER)) {
            "M8B DNGPrivateData manufacturer identifier mismatch"
        }
        cursor += PRIVATE_IDENTIFIER.size
        require(readBytes(bytes, cursor, PRIVATE_MAGIC.size.toLong()).contentEquals(PRIVATE_MAGIC)) {
            "M8B DNGPrivateData payload magic mismatch"
        }
        cursor += PRIVATE_MAGIC.size
        require(readUInt16(bytes, cursor.toInt()) == PRIVATE_VERSION)
        cursor += 2L
        val manifestLength = readUInt32(bytes, cursor.toInt())
        cursor += 4L
        require(manifestLength in 1..M8BComputationalDngLimits.MAX_PRIVATE_MANIFEST_BYTES)
        val declaredPixels = readUInt32(bytes, cursor.toInt())
        cursor += 4L
        require(declaredPixels == pixelCount.toLong())
        val expectedPrivateSize = checkedAdd(
            M8BComputationalDngLimits.PRIVATE_HEADER_BYTES,
            checkedAdd(
                manifestLength,
                checkedMultiply(pixelCount.toLong(), M8BComputationalDngLimits.PRIVATE_UNCERTAINTY_BYTES_PER_PIXEL, "M8B private uncertainty size overflow"),
                "M8B private payload size overflow",
            ),
            "M8B private payload size overflow",
        )
        require(entry.byteCount == expectedPrivateSize) { "M8B DNGPrivateData length does not match its bounded schema" }
        val manifestBytes = readBytes(bytes, cursor, manifestLength)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val manifest = decoder.decode(ByteBuffer.wrap(manifestBytes)).toString()
        cursor += manifestLength

        val variance = FloatArray(pixelCount)
        val effective = FloatArray(pixelCount)
        val contributors = ByteArray(pixelCount)
        val flags = ByteArray(pixelCount)
        repeat(pixelCount) { index ->
            val varianceValue = readFloat32(bytes, cursor.toInt())
            cursor += 4L
            val effectiveValue = readFloat32(bytes, cursor.toInt())
            cursor += 4L
            require(varianceValue.isFinite() && varianceValue >= 0f)
            require(effectiveValue.isFinite() && effectiveValue >= 0f)
            variance[index] = varianceValue
            effective[index] = effectiveValue
            contributors[index] = bytes[cursor.toInt()]
            cursor++
            val flag = bytes[cursor.toInt()].toInt() and 0xff
            cursor++
            require(flag and PRIVATE_FLAG_MASK.inv() == 0) { "M8B unknown private uncertainty flag" }
            flags[index] = flag.toByte()
        }
        require(cursor == entry.dataOffset + entry.byteCount)
        return ParsedPrivate(manifest, variance, effective, contributors, flags)
    }

    private fun required(entries: Map<Int, ParsedEntry>, tag: Int): ParsedEntry =
        entries[tag] ?: throw IllegalArgumentException("M8B required DNG tag $tag is missing")

    private fun scalarLongOrShort(bytes: ByteArray, entry: ParsedEntry): Long {
        require(entry.count == 1L)
        return when (entry.type) {
            TYPE_SHORT -> readUInt16(bytes, entry.dataOffset.toInt()).toLong()
            TYPE_LONG -> readUInt32(bytes, entry.dataOffset.toInt())
            else -> throw IllegalArgumentException("M8B TIFF scalar tag ${entry.tag} has the wrong type")
        }
    }

    private fun byteValues(bytes: ByteArray, entry: ParsedEntry): List<Int> {
        require(entry.type == TYPE_BYTE && entry.count <= Int.MAX_VALUE.toLong())
        return List(entry.count.toInt()) { index -> bytes[(entry.dataOffset + index).toInt()].toInt() and 0xff }
    }

    private fun shortValues(bytes: ByteArray, entry: ParsedEntry): List<Int> {
        require(entry.type == TYPE_SHORT && entry.count <= Int.MAX_VALUE.toLong())
        return List(entry.count.toInt()) { index -> readUInt16(bytes, (entry.dataOffset + index * 2L).toInt()) }
    }

    private fun longValues(bytes: ByteArray, entry: ParsedEntry): List<Long> {
        require(entry.type == TYPE_LONG && entry.count <= M8BComputationalDngLimits.MAX_STRIPS.toLong())
        return List(entry.count.toInt()) { index -> readUInt32(bytes, (entry.dataOffset + index * 4L).toInt()) }
    }

    private fun longOrShortValues(bytes: ByteArray, entry: ParsedEntry): List<Long> = when (entry.type) {
        TYPE_SHORT -> shortValues(bytes, entry).map(Int::toLong)
        TYPE_LONG -> {
            require(entry.count <= Int.MAX_VALUE.toLong())
            List(entry.count.toInt()) { index -> readUInt32(bytes, (entry.dataOffset + index * 4L).toInt()) }
        }
        else -> throw IllegalArgumentException("M8B TIFF tag ${entry.tag} must be SHORT or LONG")
    }

    private fun srationalValues(bytes: ByteArray, entry: ParsedEntry): List<Double> {
        require(entry.type == TYPE_SRATIONAL && entry.count <= 16L)
        return List(entry.count.toInt()) { index ->
            val offset = entry.dataOffset + index * 8L
            val numerator = readInt32(bytes, offset.toInt())
            val denominator = readInt32(bytes, offset.toInt() + 4)
            require(denominator > 0) { "M8B DNG SRATIONAL denominator must be positive" }
            numerator.toDouble() / denominator.toDouble()
        }
    }

    private fun asciiValue(bytes: ByteArray, entry: ParsedEntry): String {
        require(entry.type == TYPE_ASCII && entry.count in 2..(M8BComputationalDngLimits.MAX_UNIQUE_CAMERA_MODEL_BYTES + 1).toLong())
        val raw = readBytes(bytes, entry.dataOffset, entry.byteCount)
        require(raw.last() == 0.toByte()) { "M8B TIFF ASCII field must be NUL terminated" }
        require(raw.dropLast(1).all { (it.toInt() and 0xff) in 0x20..0x7e })
        return raw.copyOf(raw.size - 1).toString(Charsets.US_ASCII)
    }

    private fun requireRange(bytes: ByteArray, offset: Long, count: Long, label: String) {
        require(offset >= 0L && count >= 0L) { "$label has a negative extent" }
        val end = checkedAdd(offset, count, "$label overflow")
        require(end <= bytes.size.toLong()) { "$label lies outside the supplied file" }
    }

    private fun readBytes(bytes: ByteArray, offset: Long, count: Long): ByteArray {
        require(count <= Int.MAX_VALUE.toLong())
        requireRange(bytes, offset, count, "M8B byte slice")
        return bytes.copyOfRange(offset.toInt(), (offset + count).toInt())
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset.toLong() + 2L <= bytes.size.toLong())
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset.toLong() + 4L <= bytes.size.toLong())
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        val value = readUInt32(bytes, offset)
        return value.toInt()
    }

    private fun readFloat32(bytes: ByteArray, offset: Int): Float = java.lang.Float.intBitsToFloat(readInt32(bytes, offset))

    private fun typeSize(type: Int): Int = when (type) {
        TYPE_BYTE, TYPE_ASCII -> 1
        TYPE_SHORT -> 2
        TYPE_LONG -> 4
        TYPE_SRATIONAL -> 8
        else -> throw IllegalArgumentException("M8B unsupported TIFF type $type")
    }

    private fun Long.toIntExact(label: String): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) { "$label does not fit the bounded JVM reference parser" }
        return toInt()
    }

    private data class ParsedEntry(
        val tag: Int,
        val type: Int,
        val count: Long,
        val dataOffset: Long,
        val byteCount: Long,
    )

    private data class ParsedPrivate(
        val manifest: String,
        val variance: FloatArray,
        val effective: FloatArray,
        val contributors: ByteArray,
        val flags: ByteArray,
    )

    private const val TIFF_HEADER_BYTES = 8
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
    const val PHOTOMETRIC_CFA = 32803
    private const val ORIENTATION_TOP_LEFT = 1
    private const val PLANAR_CHUNKY = 1
    private const val SAMPLE_FORMAT_IEEE_FLOAT = 3
    private const val CFA_LAYOUT_RECTANGULAR = 1
    private const val CFA_RED = 0
    private const val CFA_GREEN = 1
    private const val CFA_BLUE = 2

    private val PRIVATE_IDENTIFIER = byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte(), 0)
    private val PRIVATE_MAGIC = byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte())
    private const val PRIVATE_VERSION = 1
    private const val PRIVATE_FLAG_MASK = 0x0f
}
