package com.sahidcode404.camx.core.camera.acquisition

import com.sahidcode404.camx.core.camera.model.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalRasterHasherTest {
    @Test
    fun canonicalHashIgnoresRowPaddingButPreservesMeaningfulSamples() {
        val descriptor = sensorDescriptor()
        val withNinePadding = SourcePlane(0, byteArrayOf(1, 2, 3, 4, 9, 9, 5, 6, 7, 8))
        val withZeroPadding = SourcePlane(0, byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))
        val changedSample = SourcePlane(0, byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 9))
        val first = CanonicalRasterHasher.hash(descriptor, listOf(withNinePadding))
        val second = CanonicalRasterHasher.hash(descriptor, listOf(withZeroPadding))
        val third = CanonicalRasterHasher.hash(descriptor, listOf(changedSample))
        assertEquals(first, second)
        assertEquals(8L, first.byteCount)
        assertNotEquals(first.sha256, third.sha256)
    }

    @Test
    fun sourcePlaneIsSnapshottedBeforeCallerCanMutateInput() {
        val bytes = byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8)
        val plane = SourcePlane(0, bytes)
        bytes.fill(42)
        val expected = CanonicalRasterHasher.hash(
            sensorDescriptor(),
            listOf(SourcePlane(0, byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))),
        )
        assertEquals(expected, CanonicalRasterHasher.hash(sensorDescriptor(), listOf(plane)))
    }

    @Test
    fun malformedPlaneLengthFailsBeforeHashAcceptance() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalRasterHasher.hash(
                sensorDescriptor(),
                listOf(SourcePlane(0, byteArrayOf(1, 2, 3, 4, 0, 0, 5))),
            )
        }
    }

    @Test
    fun processedSourceCannotAcquireSensorCanonicalHash() {
        val descriptor = RepresentationDescriptor(
            representation = Yuv4208,
            sourceFormat = PublicSourceFormat.YUV_420_888,
            packing = SamplePacking.PLANAR_8,
            storedBits = 8,
            effectiveBits = 8,
            size = IntSize(2, 2),
            activeArea = IntRect(0, 0, 2, 2),
            planeDescriptors = listOf(AcquisitionPlaneDescriptor(0, 0, 2, 2, 2, 1)),
            cfaPattern = null,
            sensorPixelMode = SensorPixelMode.DEFAULT,
            colorCalibrationIdentity = null,
            calibration = CalibrationEvidence(null, null, 0.0),
            sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalRasterHasher.hash(descriptor, listOf(SourcePlane(0, byteArrayOf(1, 2, 3, 4))))
        }
    }

    @Test
    fun descriptorHashIsDeterministicAndBindsInterpretation() {
        val first = sensorDescriptor(fields = listOf(InterpretationField("b", "2"), InterpretationField("a", "1")))
        val second = sensorDescriptor(fields = listOf(InterpretationField("a", "1"), InterpretationField("b", "2")))
        val changed = sensorDescriptor(fields = listOf(InterpretationField("a", "1"), InterpretationField("b", "3")))
        assertEquals(CanonicalRasterHasher.descriptorSha256(first), CanonicalRasterHasher.descriptorSha256(second))
        assertNotEquals(CanonicalRasterHasher.descriptorSha256(first), CanonicalRasterHasher.descriptorSha256(changed))
    }
}
