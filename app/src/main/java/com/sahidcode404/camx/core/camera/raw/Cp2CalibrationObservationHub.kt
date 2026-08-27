package com.sahidcode404.camx.core.camera.raw

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import java.util.concurrent.atomic.AtomicReference

/**
 * CP2 bridge for exact static Camera2 calibration metadata observed at the existing RAW boundary.
 * It owns no Camera2 resources and deliberately records only immutable copies of public metadata.
 */
internal data class Cp2StaticCalibrationObservation(
    val cfaArrangement: Int?,
    val activeArray: Rect?,
    val preCorrectionActiveArray: Rect?,
    val blackLevels: List<Int>?,
    val whiteLevel: Int?,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
)

internal object Cp2CalibrationObservationHub {
    private val latest = AtomicReference<Cp2StaticCalibrationObservation?>(null)

    fun observeStatic(characteristics: CameraCharacteristics) {
        val blackLevels = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
            IntArray(4).also { values -> pattern.copyTo(values, 0) }.toList()
        }
        latest.set(
            Cp2StaticCalibrationObservation(
                cfaArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
                activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let(::Rect),
                preCorrectionActiveArray = characteristics
                    .get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    ?.let(::Rect),
                blackLevels = blackLevels,
                whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
                referenceIlluminant1 = characteristics
                    .get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
                    ?.toInt(),
                referenceIlluminant2 = characteristics
                    .get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
                    ?.toInt(),
            ),
        )
    }

    fun snapshot(): Cp2StaticCalibrationObservation? = latest.get()
}
