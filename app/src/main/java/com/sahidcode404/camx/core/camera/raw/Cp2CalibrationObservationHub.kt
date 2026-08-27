package com.sahidcode404.camx.core.camera.raw

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CP2 bridge for exact Camera2 calibration metadata observed at the existing RAW boundary.
 * It owns no Camera2 resources. Missing optional fields remain absent and can never invalidate CP1.
 */
internal object Cp2CalibrationObservationHub {
    private data class ActiveBurst(
        val id: Long,
        val expectedFrames: Int,
        val dynamicByOrdinal: LinkedHashMap<Int, Cp2DynamicCalibrationObservation>,
        val resultObserver: AutoCloseable,
    )

    private val latestStatic = AtomicReference<Cp2StaticCalibrationObservation?>(null)
    private val lock = Any()
    private var nextBurstId = 0L
    private var activeBurst: ActiveBurst? = null

    fun observeStatic(context: RawCaptureContext, characteristics: CameraCharacteristics) {
        val blackLevels = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
            IntArray(4).also { values -> pattern.copyTo(values, 0) }.toList()
        }
        latestStatic.set(
            Cp2StaticCalibrationObservation(
                canonicalLensFingerprint = context.canonicalLensFingerprint,
                cameraProfileFingerprint = context.cameraProfileFingerprint,
                routeId = context.routeId,
                rawSize = context.rawSize,
                cfaArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
                activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toEvidence(),
                preCorrectionActiveArray = characteristics
                    .get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    ?.toEvidence(),
                blackLevels = blackLevels,
                whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
                referenceIlluminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
                    ?.let { it.toInt() and 0xff },
                referenceIlluminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
                    ?.let { it.toInt() and 0xff },
                colorTransform1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toEvidence(),
                colorTransform2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toEvidence(),
                calibrationTransform1 = characteristics
                    .get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
                    ?.toEvidence(),
                calibrationTransform2 = characteristics
                    .get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
                    ?.toEvidence(),
                forwardMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toEvidence(),
                forwardMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toEvidence(),
            ),
        )
    }

    fun beginBurst(expectedFrames: Int): Cp2BurstObservationLease {
        require(expectedFrames in M4BurstLimits.MIN_FRAMES..M4BurstLimits.MAX_FRAMES)
        synchronized(lock) {
            check(activeBurst == null) { "CP2 already has an active RAW burst observation" }
            check(nextBurstId < Long.MAX_VALUE) { "CP2 burst observation ID space exhausted" }
            val id = ++nextBurstId
            val observer = RawBurstResultObservationHub.install { timestampNs, ordinal, result ->
                if (result is CaptureResult) {
                    runCatching { observeDynamic(id, timestampNs, ordinal, result) }
                }
            }
            activeBurst = ActiveBurst(
                id = id,
                expectedFrames = expectedFrames,
                dynamicByOrdinal = LinkedHashMap(expectedFrames),
                resultObserver = observer,
            )
            return Cp2BurstObservationLease(id)
        }
    }

    private fun observeDynamic(
        burstId: Long,
        timestampNs: Long,
        ordinal: Int,
        result: CaptureResult,
    ) {
        // SENSOR_DYNAMIC_BLACK_LEVEL and SENSOR_DYNAMIC_WHITE_LEVEL were added in API 24. CamX2's
        // minSdk remains 23, so API-23 devices truthfully report these optional fields as absent.
        // SENSOR_NOISE_PROFILE is available on the Camera2 baseline and remains independently read.
        val dynamicBlackLevels: List<Double>?
        val dynamicWhiteLevel: Int?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dynamicBlackLevels = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                ?.map(Float::toDouble)
            dynamicWhiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        } else {
            dynamicBlackLevels = null
            dynamicWhiteLevel = null
        }

        val observation = Cp2DynamicCalibrationObservation(
            ordinal = ordinal,
            sensorTimestampNs = timestampNs,
            dynamicBlackLevels = dynamicBlackLevels,
            dynamicWhiteLevel = dynamicWhiteLevel,
            noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.map { pair ->
                Cp2NoiseCoefficient(
                    shotSlope = pair.first,
                    readVariance = pair.second,
                )
            },
        )
        synchronized(lock) {
            val active = activeBurst ?: return
            if (active.id != burstId || ordinal !in 0 until active.expectedFrames) return
            if (!active.dynamicByOrdinal.containsKey(ordinal)) {
                active.dynamicByOrdinal[ordinal] = observation
            }
        }
    }

    private fun finish(burstId: Long, frameSet: ImmutableRawFrameSet): Cp2CalibrationBundle {
        val static: Cp2StaticCalibrationObservation?
        val dynamic: List<Cp2DynamicCalibrationObservation>
        val observer: AutoCloseable
        synchronized(lock) {
            val active = checkNotNull(activeBurst) { "CP2 burst observation already closed" }
            check(active.id == burstId) { "CP2 burst observation lease is stale" }
            check(active.expectedFrames == frameSet.frames.size) { "CP2 expected frame membership diverged" }
            activeBurst = null
            static = latestStatic.get()
            dynamic = active.dynamicByOrdinal.values.toList()
            observer = active.resultObserver
        }
        observer.closeQuietly()
        return Cp2CalibrationAssembler.assemble(frameSet, static, dynamic)
    }

    private fun cancel(burstId: Long) {
        val observer = synchronized(lock) {
            val active = activeBurst ?: return
            if (active.id != burstId) return
            activeBurst = null
            active.resultObserver
        }
        observer.closeQuietly()
    }

    internal class Cp2BurstObservationLease internal constructor(
        private val burstId: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        fun finish(frameSet: ImmutableRawFrameSet): Cp2CalibrationBundle {
            check(closed.compareAndSet(false, true)) { "CP2 burst observation lease already consumed" }
            return try {
                Cp2CalibrationObservationHub.finish(burstId, frameSet)
            } catch (failure: Throwable) {
                // finish() normally removes the active observation before assembling. If validation
                // fails earlier, explicitly tear down the observer even though the lease is consumed.
                Cp2CalibrationObservationHub.cancel(burstId)
                throw failure
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) Cp2CalibrationObservationHub.cancel(burstId)
        }
    }

    private fun Rect.toEvidence() = Cp2RectEvidence(left, top, right, bottom)

    private fun ColorSpaceTransform.toEvidence(): Cp2Matrix3x3Evidence = Cp2Matrix3x3Evidence(
        buildList(9) {
            for (row in 0..2) {
                for (column in 0..2) {
                    val rational = getElement(column, row)
                    add(Cp2RationalEvidence(rational.numerator, rational.denominator))
                }
            }
        },
    )

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }
}
