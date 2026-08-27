from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


cp3_path = Path("app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/Cp3ComputationalRawEngine.kt")
cp3 = cp3_path.read_text()
cp3 = replace_once(
    cp3,
    '    const val ALGORITHM_ID = "cp3.raw16.cfa-known-noise-fusion-v3-u16"\n    const val ALGORITHM_VERSION = 3\n',
    '    const val ALGORITHM_ID = "cp3.raw16.cfa-known-noise-fusion-v4-noalloc"\n    const val ALGORITHM_VERSION = 4\n',
    "cp3 algorithm version",
)

fusion_start = cp3.index("        val acceptedByOrdinal = accepted.associateBy { it.frame.ordinal }")
fusion_end = cp3.index("\n        if (multiFramePixels == 0L)", fusion_start)
fusion_block = '''        val acceptedByOrdinal = accepted.associateBy { it.frame.ordinal }
        val alignmentByOrdinal = evidence.associateBy { it.ordinal }
        val includedOrdinals = accepted.map { it.frame.ordinal }.sorted()
        val fusionFrames = includedOrdinals.map { ordinal ->
            val calibrationForFrame = checkNotNull(acceptedByOrdinal[ordinal])
            val alignment = checkNotNull(alignmentByOrdinal[ordinal])
            FusionFrame(
                calibration = calibrationForFrame,
                dxPixels = alignment.dxPixels,
                dyPixels = alignment.dyPixels,
            )
        }
        val referenceFusion = checkNotNull(
            fusionFrames.firstOrNull { it.calibration.frame.ordinal == referenceOrdinal },
        )
        val signalDn = ShortArray(activePixels.toInt())
        var multiFramePixels = 0L
        var referenceOnlyPixels = 0L
        var censoredPixels = 0L
        var rejectedMeasurements = 0L
        var outputIndex = 0
        var y = active.top
        val activeBottom = active.top + active.height
        val activeRight = active.left + active.width

        // Full-resolution CP3 is deliberately allocation-free inside the pixel/frame loops. The old
        // SensorSample data-class path created tens of millions of short-lived objects on a 12 MP
        // eight-frame burst and could make ART appear hung in GC. All hot-path state below is scalar.
        while (y < activeBottom) {
            var x = active.left
            while (x < activeRight) {
                val site = ((y and 1) shl 1) or (x and 1)
                val referenceCalibration = referenceFusion.calibration
                val referenceRaw = referenceCalibration.frame.raw16LittleEndianAtUnchecked(x, y)
                val referenceSignal = sampleSignalDn(referenceCalibration, referenceRaw, site)
                if (sampleCensored(referenceCalibration, referenceRaw, site)) {
                    signalDn[outputIndex] = finiteU16(referenceSignal, "CP3 reference signal")
                    referenceOnlyPixels++
                    censoredPixels++
                    outputIndex++
                    x++
                    continue
                }
                val referenceVariance = sampleVarianceDn2(referenceCalibration, referenceSignal, site)

                var sumWeight = 0.0
                var sumWeightedSignal = 0.0
                var contributorCount = 0
                var frameIndex = 0
                while (frameIndex < fusionFrames.size) {
                    val fusionFrame = fusionFrames[frameIndex]
                    val candidate = fusionFrame.calibration
                    val mappedX = x + fusionFrame.dxPixels
                    val mappedY = y + fusionFrame.dyPixels
                    if (!inside(active, mappedX, mappedY)) {
                        rejectedMeasurements++
                        frameIndex++
                        continue
                    }
                    val candidateSite = ((mappedY and 1) shl 1) or (mappedX and 1)
                    val candidateRaw = candidate.frame.raw16LittleEndianAtUnchecked(mappedX, mappedY)
                    val candidateSignal = sampleSignalDn(candidate, candidateRaw, candidateSite)
                    if (sampleCensored(candidate, candidateRaw, candidateSite)) {
                        rejectedMeasurements++
                        frameIndex++
                        continue
                    }
                    val candidateVariance = sampleVarianceDn2(candidate, candidateSignal, candidateSite)
                    if (candidate.frame.ordinal != referenceOrdinal) {
                        val residualVariance = max(
                            MIN_VARIANCE_DN2,
                            referenceVariance + candidateVariance,
                        )
                        val residual = referenceSignal - candidateSignal
                        if (!residual.isFinite() ||
                            residual * residual >
                            PER_PIXEL_RESIDUAL_SIGMA * PER_PIXEL_RESIDUAL_SIGMA * residualVariance
                        ) {
                            rejectedMeasurements++
                            frameIndex++
                            continue
                        }
                    }
                    val variance = max(candidateVariance, MIN_VARIANCE_DN2)
                    val weight = 1.0 / variance
                    sumWeight += weight
                    sumWeightedSignal += weight * candidateSignal
                    contributorCount++
                    frameIndex++
                }
                check(contributorCount > 0 && sumWeight > 0.0) {
                    "CP3 uncensored reference pixel must remain a deterministic fallback"
                }
                val fused = sumWeightedSignal / sumWeight
                signalDn[outputIndex] = finiteU16(fused, "CP3 fused signal")
                if (contributorCount >= 2) multiFramePixels++ else referenceOnlyPixels++
                outputIndex++
                x++
            }
            y++
        }
'''
cp3 = cp3[:fusion_start] + fusion_block + cp3[fusion_end:]

score_start = cp3.index("    private fun scoreTranslation(")
score_end = cp3.index("\n    private fun outputSha256(", score_start)
score_block = '''    private fun scoreTranslation(
        reference: FrameCalibration,
        candidate: FrameCalibration,
        active: IntRect,
        dx: Int,
        dy: Int,
    ): AlignmentScore {
        check(dx % 2 == 0 && dy % 2 == 0) { "CP3 alignment must preserve 2x2 CFA phase" }
        var pairs = 0
        var inliers = 0
        var sumNormalizedSquaredResidual = 0.0
        var y = active.top
        while (y < active.top + active.height) {
            var x = active.left
            while (x < active.left + active.width) {
                val mappedX = x + dx
                val mappedY = y + dy
                if (inside(active, mappedX, mappedY)) {
                    val referenceSite = ((y and 1) shl 1) or (x and 1)
                    val candidateSite = ((mappedY and 1) shl 1) or (mappedX and 1)
                    val referenceRaw = reference.frame.raw16LittleEndianAtUnchecked(x, y)
                    val candidateRaw = candidate.frame.raw16LittleEndianAtUnchecked(mappedX, mappedY)
                    if (!sampleCensored(reference, referenceRaw, referenceSite) &&
                        !sampleCensored(candidate, candidateRaw, candidateSite)
                    ) {
                        val referenceSignal = sampleSignalDn(reference, referenceRaw, referenceSite)
                        val candidateSignal = sampleSignalDn(candidate, candidateRaw, candidateSite)
                        val residualVariance = max(
                            MIN_VARIANCE_DN2,
                            sampleVarianceDn2(reference, referenceSignal, referenceSite) +
                                sampleVarianceDn2(candidate, candidateSignal, candidateSite),
                        )
                        val residual = referenceSignal - candidateSignal
                        val normalizedSquared = residual * residual / residualVariance
                        if (normalizedSquared.isFinite()) {
                            pairs++
                            sumNormalizedSquaredResidual += normalizedSquared
                            if (normalizedSquared <= PER_PIXEL_RESIDUAL_SIGMA * PER_PIXEL_RESIDUAL_SIGMA) inliers++
                        }
                    }
                }
                x += SAMPLE_STEP_PIXELS
            }
            y += SAMPLE_STEP_PIXELS
        }
        return AlignmentScore(
            dxPixels = dx,
            dyPixels = dy,
            meanNormalizedSquaredResidual = if (pairs == 0) Double.POSITIVE_INFINITY
            else sumNormalizedSquaredResidual / pairs.toDouble(),
            sampledPairs = pairs,
            inlierFraction = if (pairs == 0) 0.0 else inliers.toDouble() / pairs.toDouble(),
        )
    }

    private fun sampleSignalDn(frame: FrameCalibration, raw: Int, site: Int): Double {
        val black = frame.blackLevels[site]
        val range = frame.whiteLevel - black
        check(range > 0.0 && range.isFinite()) { "CP3 requires a positive real per-site sensor range" }
        return (raw.toDouble() - black).coerceIn(0.0, range)
    }

    private fun sampleVarianceDn2(frame: FrameCalibration, signalDn: Double, site: Int): Double {
        val black = frame.blackLevels[site]
        val range = frame.whiteLevel - black
        check(range > 0.0 && range.isFinite()) { "CP3 requires a positive real per-site sensor range" }
        val noise = checkNotNull(frame.noiseProfile)[site]
        val normalized = signalDn / range
        val normalizedVariance = noise.shotSlope * normalized + noise.readVariance
        return max(normalizedVariance * range * range, 0.0)
    }

    private fun sampleCensored(frame: FrameCalibration, raw: Int, site: Int): Boolean {
        val black = frame.blackLevels[site]
        return raw.toDouble() <= black || raw.toDouble() >= frame.whiteLevel
    }
'''
cp3 = cp3[:score_start] + score_block + cp3[score_end:]

cp3 = replace_once(
    cp3,
    '''        // V3 hashes the actual persistent U16 fused raster plus immutable provenance. Noise and
        // contributor evidence still controls fusion and report counters but is not duplicated into
        // full-resolution persistent diagnostic arrays.
        signalDn.forEach { updateInt(it.toInt() and 0xffff) }
''',
    '''        // V4 hashes the actual persistent U16 raster in bounded chunks. The previous per-sample
        // updateInt path made four MessageDigest calls for every pixel, which was another avoidable
        // full-resolution CPU hot spot after fusion completed.
        val rasterBytes = ByteArray(16 * 1024)
        var rasterOffset = 0
        signalDn.forEach { sample ->
            val value = sample.toInt() and 0xffff
            rasterBytes[rasterOffset++] = value.toByte()
            rasterBytes[rasterOffset++] = (value ushr 8).toByte()
            if (rasterOffset == rasterBytes.size) {
                digest.update(rasterBytes)
                rasterOffset = 0
            }
        }
        if (rasterOffset > 0) digest.update(rasterBytes, 0, rasterOffset)
''',
    "cp3 raster hash",
)

cp3 = replace_once(
    cp3,
    '''    private data class SensorSample(
        val signalDn: Double,
        val knownVarianceDn2: Double,
        val censored: Boolean,
    )
''',
    '''    private data class FusionFrame(
        val calibration: FrameCalibration,
        val dxPixels: Int,
        val dyPixels: Int,
    )
''',
    "cp3 hot-path model",
)

cp3 = replace_once(
    cp3,
    '''    internal fun signalDnAt(index: Int): Float {
        require(index in signalDn.indices) { "CP3 fused signal index is outside the active raster" }
        return (signalDn[index].toInt() and 0xffff).toFloat()
    }
''',
    '''    internal fun signalDnAt(index: Int): Float {
        require(index in signalDn.indices) { "CP3 fused signal index is outside the active raster" }
        return (signalDn[index].toInt() and 0xffff).toFloat()
    }

    internal fun writeU16LittleEndian(
        startIndex: Int,
        count: Int,
        maxValue: Int,
        destination: ByteArray,
    ) {
        require(startIndex >= 0 && count >= 0 && startIndex.toLong() + count.toLong() <= signalDn.size.toLong())
        require(maxValue in 1..0xffff)
        require(destination.size >= count * 2)
        var sourceIndex = startIndex
        var destinationIndex = 0
        val end = startIndex + count
        while (sourceIndex < end) {
            val value = minOf(signalDn[sourceIndex].toInt() and 0xffff, maxValue)
            destination[destinationIndex++] = value.toByte()
            destination[destinationIndex++] = (value ushr 8).toByte()
            sourceIndex++
        }
    }
''',
    "cp3 bulk U16 export",
)
cp3_path.write_text(cp3)

raw_path = Path("app/src/main/java/com/sahidcode404/camx/core/camera/raw/RawBurstModels.kt")
raw = raw_path.read_text()
marker = "    internal fun writeCanonicalRaster(output: OutputStream) {"
if raw.count(marker) != 1:
    raise SystemExit("raw unchecked read insertion marker missing or duplicated")
unchecked = '''    /** Internal hot-path read. Bounds are proven by CP3 active/mapped-coordinate checks. */
    internal fun raw16LittleEndianAtUnchecked(x: Int, y: Int): Int {
        val pixelIndex = y * rawSize.width + x
        val byteIndex = pixelIndex shl 1
        return (canonicalRaster[byteIndex].toInt() and 0xff) or
            ((canonicalRaster[byteIndex + 1].toInt() and 0xff) shl 8)
    }

'''
raw = raw.replace(marker, unchecked + marker, 1)
raw_path.write_text(raw)

cp4_path = Path("app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt")
cp4 = cp4_path.read_text()
cp4 = replace_once(
    cp4,
    " * CP4 writes the production-connected CP3 fused CFA as one standards-shaped float DNG.\n",
    " * CP4 writes the production-connected CP3 fused CFA as one standards-shaped unsigned-16 DNG.\n",
    "cp4 documentation",
)
cp4 = replace_once(
    cp4,
    '''        for (index in 0 until fused.pixelCount) {
            val sample = fused.signalDnAt(index)
            require(sample.isFinite() && sample >= 0f) { "CP4 fused sample is not finite non-negative sensor signal" }
            writeUInt16(counted, minOf(sample.toInt(), outputWhite.toInt()))
        }
''',
    '''        val rowBytesLong = checkedMultiply(width.toLong(), UINT16_BYTES, "CP4 row byte count overflow")
        require(rowBytesLong <= Int.MAX_VALUE.toLong()) { "CP4 row cannot be addressed by a JVM byte array" }
        val rowBytes = ByteArray(rowBytesLong.toInt())
        var firstPixel = 0
        repeat(height) {
            fused.writeU16LittleEndian(
                startIndex = firstPixel,
                count = width,
                maxValue = outputWhite.toInt(),
                destination = rowBytes,
            )
            counted.write(rowBytes)
            firstPixel += width
        }
''',
    "cp4 row-buffered raster write",
)
cp4 = replace_once(
    cp4,
    '''    private fun writePadding(output: CountingOutputStream, count: Long) {
        require(count >= 0L && count <= MAX_METADATA_BYTES)
        repeat(count.toInt()) { output.write(0) }
    }

    private fun writeUInt16(output: OutputStream, value: Int) {
        require(value in 0..0xffff)
        output.write(value)
        output.write(value ushr 8)
    }
''',
    '''    private fun writePadding(output: CountingOutputStream, count: Long) {
        require(count >= 0L && count <= MAX_METADATA_BYTES)
        if (count == 0L) return
        val zeros = ByteArray(minOf(8 * 1024, count.toInt()))
        var remaining = count
        while (remaining > 0L) {
            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
            output.write(zeros, 0, chunk)
            remaining -= chunk.toLong()
        }
    }
''',
    "cp4 bounded bulk padding",
)
cp4_path.write_text(cp4)
