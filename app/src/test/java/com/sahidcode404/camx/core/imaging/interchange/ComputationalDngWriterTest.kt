package com.sahidcode404.camx.core.imaging.interchange

import com.sahidcode404.camx.core.imaging.calibration.ColorMatrixCalibration
import com.sahidcode404.camx.core.imaging.calibration.ColorMatrixEntry
import com.sahidcode404.camx.core.imaging.calibration.Matrix3x3
import com.sahidcode404.camx.core.imaging.calibration.ReferenceIlluminant
import com.sahidcode404.camx.core.imaging.reconstruction.M7ReconstructionTestFixtures
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputationalDngWriterTest {
    @Test
    fun deterministicFloatCfaWriteRoundTripsM7RadianceAndUncertainty() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            offsets = listOf(10, -10),
            colorCalibration = M7ReconstructionTestFixtures.identityColorCalibration(),
        )
        val negative = M7ReconstructionTestFixtures.reconstruct(measurements)
        val authority = ComputationalCfaDngAuthority("CamX2 Test Sensor", measurements.profile)

        val first = write(negative, authority)
        val second = write(negative, authority)
        assertArrayEquals(first.bytes, second.bytes)
        assertEquals(first.receipt.sha256, second.receipt.sha256)
        assertEquals(first.bytes.size.toLong(), first.receipt.byteCount)
        assertEquals(negative.provenance.outputSha256, first.receipt.sourceOutputSha256)

        val parsed = ComputationalDngInspector.inspect(first.bytes)
        assertEquals(negative.width, parsed.width)
        assertEquals(negative.height, parsed.height)
        assertEquals("CamX2 Test Sensor", parsed.uniqueCameraModel)
        assertEquals(listOf(0, 1, 1, 2), parsed.cfaPattern)
        assertEquals(4095L, parsed.outputWhiteLevelDn)
        assertEquals(21, parsed.calibrationIlluminant1)
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), parsed.colorMatrix1)
        assertTrue(parsed.privateManifest.contains("outputSha256=${negative.provenance.outputSha256}"))
        assertTrue(parsed.privateManifest.contains("m7ManifestSha256=${negative.provenance.manifestSha256}"))
        assertTrue(parsed.privateManifest.contains("learnedPriorChangedPixels=false"))

        for (y in 0 until negative.height) {
            for (x in 0 until negative.width) {
                val source = negative.sampleAt(negative.activeArea.left + x, negative.activeArea.top + y)
                val uncertainty = parsed.uncertaintyAt(x, y)
                assertEquals(source.radianceDn, parsed.radianceAt(x, y), 0.0)
                assertEquals(source.varianceDn2, uncertainty.varianceDn2, 0.0)
                assertEquals(source.effectiveSampleCount, uncertainty.effectiveSampleCount, 0.0)
                assertEquals(source.contributingFrames, uncertainty.contributingFrames)
                assertEquals(source.lowCensored, uncertainty.lowCensored)
                assertEquals(source.highCensored, uncertainty.highCensored)
                assertEquals(source.referenceOnly, uncertainty.referenceOnly)
                assertEquals(source.measurementValid, uncertainty.measurementValid)
            }
        }
    }

    @Test
    fun missingColorCalibrationIsRejectedWithoutFabricatingMetadata() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(0, 0))
        val negative = M7ReconstructionTestFixtures.reconstruct(measurements)
        val output = ByteArrayOutputStream()

        val error = runCatching {
            ComputationalDngWriter().writeCfa(
                negative,
                ComputationalCfaDngAuthority("CamX2 No Color", measurements.profile),
                output,
                TEST_BUDGET_BYTES,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("color calibration", ignoreCase = true) == true)
        assertEquals(0, output.size())
    }

    @Test
    fun foreignCalibrationDigestIsRejectedBeforeWriting() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            listOf(0, 0),
            colorCalibration = M7ReconstructionTestFixtures.identityColorCalibration(),
        )
        val negative = M7ReconstructionTestFixtures.reconstruct(measurements)
        val foreign = M7ReconstructionTestFixtures.measurementsFromOffsets(
            listOf(0, 0),
            colorCalibration = alternateColorCalibration(),
        )
        val output = ByteArrayOutputStream()

        val error = runCatching {
            ComputationalDngWriter().writeCfa(
                negative,
                ComputationalCfaDngAuthority("CamX2 Test Sensor", foreign.profile),
                output,
                TEST_BUDGET_BYTES,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, output.size())
    }

    @Test
    fun uniqueCameraModelCannotBeBlankOrUnbounded() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            listOf(0, 0),
            colorCalibration = M7ReconstructionTestFixtures.identityColorCalibration(),
        )
        assertTrue(runCatching { ComputationalCfaDngAuthority("", measurements.profile) }.isFailure)
        assertTrue(
            runCatching {
                ComputationalCfaDngAuthority("x".repeat(M8BComputationalDngLimits.MAX_UNIQUE_CAMERA_MODEL_BYTES + 1), measurements.profile)
            }.isFailure,
        )
    }

    @Test
    fun censoredBoundaryAndPrivateFlagsSurviveInterchange() {
        val truth = M7ReconstructionTestFixtures.truthValues()
        val targetX = 3
        val targetY = 3
        val target = targetY * M7ReconstructionTestFixtures.size.width + targetX
        val first = truth.copyOf().also { it[target] = 4095 }
        val second = truth.copyOf().also { it[target] = 4095 }
        val measurements = M7ReconstructionTestFixtures.measurementsFromRasters(
            listOf(first, second),
            colorCalibration = M7ReconstructionTestFixtures.identityColorCalibration(),
        )
        val negative = M7ReconstructionTestFixtures.reconstruct(measurements)
        val parsed = ComputationalDngInspector.inspect(
            write(negative, ComputationalCfaDngAuthority("CamX2 Test Sensor", measurements.profile)).bytes,
        )

        val source = negative.sampleAt(targetX, targetY)
        val decoded = parsed.uncertaintyAt(targetX, targetY)
        assertTrue(source.highCensored)
        assertTrue(decoded.highCensored)
        assertFalse(decoded.measurementValid)
        assertEquals(4095.0, parsed.radianceAt(targetX, targetY), 0.0)
    }

    @Test
    fun outputAdmissionFailsBeforeAnyAllocationHeavyWrite() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            listOf(0, 0),
            colorCalibration = M7ReconstructionTestFixtures.identityColorCalibration(),
        )
        val negative = M7ReconstructionTestFixtures.reconstruct(measurements)
        val output = ByteArrayOutputStream()

        val error = runCatching {
            ComputationalDngWriter().writeCfa(
                negative,
                ComputationalCfaDngAuthority("CamX2 Test Sensor", measurements.profile),
                output,
                1L,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, output.size())
    }

    @Test
    fun malformedStripOffsetIsRejectedByBoundedInspector() {
        val good = validBytes()
        val corrupted = good.copyOf()
        val stripOffsetEntry = findIfdEntry(corrupted, 273)
        putUInt32(corrupted, stripOffsetEntry + 8, 0L)

        val error = runCatching { ComputationalDngInspector.inspect(corrupted) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun duplicateTagIsRejectedByBoundedInspector() {
        val good = validBytes()
        val corrupted = good.copyOf()
        val firstEntry = 10
        val secondEntry = firstEntry + 12
        corrupted[secondEntry] = corrupted[firstEntry]
        corrupted[secondEntry + 1] = corrupted[firstEntry + 1]

        val error = runCatching { ComputationalDngInspector.inspect(corrupted) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun truncatedFileIsRejectedWithoutOutOfBoundsParsing() {
        val good = validBytes()
        val error = runCatching { ComputationalDngInspector.inspect(good.copyOf(good.size - 7)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun validBytes(): ByteArray {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            listOf(10, -10),
            colorCalibration = M7ReconstructionTestFixtures.identityColorCalibration(),
        )
        val negative = M7ReconstructionTestFixtures.reconstruct(measurements)
        return write(negative, ComputationalCfaDngAuthority("CamX2 Test Sensor", measurements.profile)).bytes
    }

    private fun write(
        negative: com.sahidcode404.camx.core.imaging.reconstruction.FusedCfaRadiance,
        authority: ComputationalCfaDngAuthority,
    ): Written {
        val output = ByteArrayOutputStream()
        val receipt = ComputationalDngWriter().writeCfa(negative, authority, output, TEST_BUDGET_BYTES)
        return Written(output.toByteArray(), receipt)
    }

    private fun alternateColorCalibration(): ColorMatrixCalibration = ColorMatrixCalibration(
        listOf(
            ColorMatrixEntry(
                ReferenceIlluminant(21, "D65"),
                Matrix3x3(
                    0.9, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                    0.0, 0.0, 1.1,
                ),
            ),
        ),
    )

    private fun findIfdEntry(bytes: ByteArray, tag: Int): Int {
        val count = readUInt16(bytes, 8)
        var offset = 10
        repeat(count) {
            if (readUInt16(bytes, offset) == tag) return offset
            offset += 12
        }
        error("tag $tag not found")
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xffL).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xffL).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xffL).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xffL).toByte()
    }

    private data class Written(val bytes: ByteArray, val receipt: ComputationalDngReceipt)

    private companion object {
        const val TEST_BUDGET_BYTES = 2L * 1024L * 1024L
    }
}
