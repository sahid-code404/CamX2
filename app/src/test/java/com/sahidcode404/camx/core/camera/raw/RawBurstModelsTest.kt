package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawBurstModelsTest {
    @Test
    fun reservationProvesSourceCopyMetadataAndSafetyPeak() {
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 3,
            rawSize = IntSize(2, 2),
            maxSourceBytesPerFrame = 16L,
            maxResidentBytes = 2L * 1024L * 1024L,
        )

        assertEquals(8L, reservation.canonicalBytesPerFrame)
        assertEquals(48L, reservation.sourceReservationBytes)
        assertEquals(24L, reservation.canonicalCopyReservationBytes)
        assertEquals(12L * 1024L, reservation.metadataReservationBytes)
        assertEquals(
            48L + 24L + 12L * 1024L + M4BurstLimits.FIXED_SAFETY_MARGIN_BYTES,
            reservation.requiredResidentBytes,
        )
    }

    @Test
    fun reservationRejectsInsufficientBudgetBeforeCapture() {
        assertThrows(IllegalArgumentException::class.java) {
            RawBurstReservation.forRawSensor(
                frameCount = 4,
                rawSize = IntSize(4000, 3000),
                maxSourceBytesPerFrame = 24_000_000L,
                maxResidentBytes = 100_000_000L,
            )
        }
    }

    @Test
    fun frameDefensivelyFreezesCanonicalRaster() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val frame = frame(0, 100L, source)
        val originalDigest = frame.canonicalSha256
        source[0] = 99
        val exposed = frame.copyCanonicalRaster()
        exposed[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.copyCanonicalRaster())
        assertEquals(originalDigest, frame.canonicalSha256)
        assertNotEquals(originalDigest, frame(0, 100L, byteArrayOf(9, 2, 3, 4, 5, 6, 7, 8)).canonicalSha256)
    }

    @Test
    fun immutableFrameSetRequiresExactContiguousMembership() {
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 2,
            rawSize = IntSize(2, 2),
            maxSourceBytesPerFrame = 8L,
            maxResidentBytes = 2L * 1024L * 1024L,
        )
        val context = context(reservation)
        val set = ImmutableRawFrameSet(
            context,
            reservation,
            listOf(
                frame(1, 200L, byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)),
                frame(0, 100L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
            ),
        )

        assertEquals(listOf(0, 1), set.frames.map { it.ordinal })
        assertEquals(16L, set.totalCanonicalBytes)

        assertThrows(IllegalArgumentException::class.java) {
            ImmutableRawFrameSet(context, reservation, listOf(frame(0, 100L, ByteArray(8))))
        }
    }

    @Test
    fun duplicateSensorTimestampIsRejected() {
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 2,
            rawSize = IntSize(2, 2),
            maxSourceBytesPerFrame = 8L,
            maxResidentBytes = 2L * 1024L * 1024L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ImmutableRawFrameSet(
                context(reservation),
                reservation,
                listOf(frame(0, 100L, ByteArray(8)), frame(1, 100L, ByteArray(8))),
            )
        }
    }

    private fun frame(ordinal: Int, timestamp: Long, bytes: ByteArray) = ImmutableRawBurstFrame(
        ordinal = ordinal,
        rawSize = IntSize(2, 2),
        sourceRowStrideBytes = 4,
        sourcePixelStrideBytes = 2,
        sourceRequiredBytes = 8L,
        canonicalRowBytes = 4,
        metadata = RawBurstFrameMetadata(
            sensorTimestampNs = timestamp,
            frameNumber = ordinal.toLong(),
            exposureTimeNs = 1_000L,
            sensitivityIso = 100,
            frameDurationNs = 33_333_333L,
        ),
        canonicalRaster = bytes,
    )

    private fun context(reservation: RawBurstReservation) = RawCaptureContext(
        captureToken = CaptureToken(1L),
        selectionGeneration = SelectionGeneration(1L),
        sessionGeneration = SessionGeneration(2L),
        canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
        cameraProfileFingerprint = CameraProfileFingerprint("profile"),
        routeId = CameraRouteId("route"),
        displayRotationAtShutter = DisplayRotation.ROTATION_0,
        sensorOrientationDegrees = 90,
        lensFacing = LensFacing.BACK,
        rawSize = reservation.rawSize,
        timeoutMillis = reservation.timeoutMillis,
    )
}
