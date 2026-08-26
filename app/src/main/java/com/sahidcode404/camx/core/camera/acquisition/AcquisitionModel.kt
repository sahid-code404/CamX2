package com.sahidcode404.camx.core.camera.acquisition

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import java.util.Collections

object M1AcquisitionLimits {
    const val MAX_PLANES = 4
    const val MAX_INTERPRETATION_FIELDS = 128
    const val MAX_INTERPRETATION_KEY_CHARS = 128
    const val MAX_INTERPRETATION_VALUE_CHARS = 1_024
    const val MAX_SOURCE_PLANE_BYTES = 512L * 1024L * 1024L
    const val MAX_CANONICAL_RASTER_BYTES = 512L * 1024L * 1024L
    const val MAX_CORPUS_ENTRIES = 16_384
    const val MAX_CORPUS_CANONICAL_BYTES = 64L * 1024L * 1024L * 1024L
}

sealed interface AcquiredRepresentation
sealed interface InterpretableSensorDomain : AcquiredRepresentation
sealed interface CameraProcessed : AcquiredRepresentation
sealed interface OpaqueTransport : AcquiredRepresentation

object MosaicSensorSamples : InterpretableSensorDomain
object MonochromeSensorSamples : InterpretableSensorDomain
object FullColorSensorSamples : InterpretableSensorDomain
object P01010 : CameraProcessed
object P21010 : CameraProcessed
object Yuv4208 : CameraProcessed
object RawPrivateToken : OpaqueTransport
object PrivateSurfaceToken : OpaqueTransport

enum class PublicSourceFormat {
    RAW_SENSOR,
    RAW10,
    RAW12,
    RAW14,
    YUV_420_888,
    P010,
    P210,
    PRIVATE_TOKEN,
}

enum class SamplePacking {
    UNPACKED_16_LE,
    PACKED_RAW10,
    PACKED_RAW12,
    PACKED_RAW14,
    PLANAR_8,
    PLANAR_10,
    OPAQUE,
}

enum class SensorPixelMode {
    DEFAULT,
    MAXIMUM_RESOLUTION,
    UNKNOWN_PUBLIC,
}

enum class CfaPattern {
    RGGB,
    GRBG,
    GBRG,
    BGGR,
}

enum class AcquisitionTimebase {
    SENSOR,
    ELAPSED_REALTIME,
    BOOTTIME,
    UNKNOWN,
}

enum class AcquisitionSourceApi {
    CAMERA2_PUBLIC,
    CAMERA_NDK_PUBLIC,
    OPAQUE_PRIVATE,
}

data class IntRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "Active-area origin cannot be negative" }
        require(width > 0 && height > 0) { "Active-area dimensions must be positive" }
    }
}

data class CalibrationEvidence(
    val identity: String?,
    val version: String?,
    val confidence: Double,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Calibration confidence must be finite and in [0, 1]"
        }
        require((identity == null) == (version == null)) {
            "Calibration identity and version must either both be present or both be absent"
        }
        require(identity == null || identity.isNotBlank()) { "Calibration identity cannot be blank" }
        require(version == null || version.isNotBlank()) { "Calibration version cannot be blank" }
        require(confidence == 0.0 || identity != null) {
            "Non-zero calibration confidence requires an identified calibration"
        }
    }
}

data class InterpretationField(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank() && key.length <= M1AcquisitionLimits.MAX_INTERPRETATION_KEY_CHARS) {
            "Interpretation key must be nonblank and bounded"
        }
        require(value.length <= M1AcquisitionLimits.MAX_INTERPRETATION_VALUE_CHARS) {
            "Interpretation value exceeds the M1 bound"
        }
    }
}

data class AcquisitionPlaneDescriptor(
    val planeIndex: Int,
    val offsetBytes: Long,
    val rowStrideBytes: Long,
    val meaningfulRowBytes: Long,
    val rowCount: Int,
    val pixelStrideBytes: Int,
) {
    init {
        require(planeIndex >= 0) { "Plane index cannot be negative" }
        require(offsetBytes >= 0L) { "Plane offset cannot be negative" }
        require(rowStrideBytes > 0L) { "Row stride must be positive" }
        require(meaningfulRowBytes > 0L) { "Meaningful row width must be positive" }
        require(rowStrideBytes >= meaningfulRowBytes) {
            "Row stride cannot be smaller than meaningful row bytes"
        }
        require(rowCount > 0) { "Plane row count must be positive" }
        require(pixelStrideBytes > 0) { "Pixel stride must be positive" }
        require(meaningfulRowBytes <= Int.MAX_VALUE.toLong()) {
            "A meaningful row must fit a JVM array slice"
        }
        require(requiredSourceBytes() <= M1AcquisitionLimits.MAX_SOURCE_PLANE_BYTES) {
            "Plane source extent exceeds the M1 bound"
        }
        require(canonicalByteCount() <= M1AcquisitionLimits.MAX_CANONICAL_RASTER_BYTES) {
            "Plane canonical extent exceeds the M1 bound"
        }
    }

    fun requiredSourceBytes(): Long = checkedAdd(
        offsetBytes,
        checkedAdd(
            checkedMultiply((rowCount - 1).toLong(), rowStrideBytes),
            meaningfulRowBytes,
        ),
    )

    fun canonicalByteCount(): Long = checkedMultiply(rowCount.toLong(), meaningfulRowBytes)
}

class RepresentationDescriptor(
    val representation: AcquiredRepresentation,
    val sourceFormat: PublicSourceFormat,
    val packing: SamplePacking,
    val storedBits: Int,
    val effectiveBits: Int,
    val size: IntSize,
    val activeArea: IntRect,
    planeDescriptors: List<AcquisitionPlaneDescriptor>,
    val cfaPattern: CfaPattern?,
    val sensorPixelMode: SensorPixelMode,
    val colorCalibrationIdentity: String?,
    val calibration: CalibrationEvidence,
    val sourceApi: AcquisitionSourceApi,
    interpretationFields: List<InterpretationField> = emptyList(),
) {
    val planes: List<AcquisitionPlaneDescriptor> = immutableList(planeDescriptors.sortedBy { it.planeIndex })
    val interpretationFields: List<InterpretationField> = immutableList(
        interpretationFields.sortedWith(compareBy(InterpretationField::key, InterpretationField::value)),
    )

    init {
        require(storedBits in 1..32) { "Stored precision must be in 1..32 bits" }
        require(effectiveBits in 1..storedBits) { "Effective precision must be in 1..storedBits" }
        require(activeArea.left.toLong() + activeArea.width.toLong() <= size.width.toLong() &&
            activeArea.top.toLong() + activeArea.height.toLong() <= size.height.toLong()
        ) { "Active area must lie within the source dimensions" }
        require(planes.isNotEmpty() && planes.size <= M1AcquisitionLimits.MAX_PLANES) {
            "Representation must declare a bounded, non-empty plane set"
        }
        require(planes.indices.all { planes[it].planeIndex == it }) {
            "Plane indices must be contiguous and start at zero"
        }
        require(interpretationFields.size <= M1AcquisitionLimits.MAX_INTERPRETATION_FIELDS) {
            "Interpretation metadata exceeds the M1 field bound"
        }
        require(interpretationFields.map(InterpretationField::key).distinct().size == interpretationFields.size) {
            "Interpretation metadata keys must be unique"
        }
        require(colorCalibrationIdentity == null || colorCalibrationIdentity.isNotBlank()) {
            "Color calibration identity cannot be blank"
        }
        validateRepresentationTruth()
        canonicalByteCount()
    }

    fun canonicalByteCount(): Long {
        var total = 0L
        planes.forEach { plane ->
            total = checkedAdd(total, plane.canonicalByteCount())
            require(total <= M1AcquisitionLimits.MAX_CANONICAL_RASTER_BYTES) {
                "Canonical raster exceeds the M1 bound"
            }
        }
        return total
    }

    internal fun representationName(): String = when (representation) {
        MosaicSensorSamples -> "MosaicSensorSamples"
        MonochromeSensorSamples -> "MonochromeSensorSamples"
        FullColorSensorSamples -> "FullColorSensorSamples"
        P01010 -> "P01010"
        P21010 -> "P21010"
        Yuv4208 -> "Yuv4208"
        RawPrivateToken -> "RawPrivateToken"
        PrivateSurfaceToken -> "PrivateSurfaceToken"
        else -> error("Unknown acquired representation")
    }

    private fun validateRepresentationTruth() {
        when (representation) {
            is InterpretableSensorDomain -> {
                require(sourceApi != AcquisitionSourceApi.OPAQUE_PRIVATE) {
                    "Interpretable sensor evidence cannot originate from an opaque private API"
                }
                require(sourceFormat in SENSOR_FORMATS) {
                    "Sensor-domain evidence requires a public interpretable RAW format"
                }
                require(packing != SamplePacking.OPAQUE) {
                    "Sensor-domain evidence cannot use opaque packing"
                }
                if (representation === MosaicSensorSamples) {
                    require(cfaPattern != null) { "Mosaic sensor evidence requires a CFA pattern" }
                } else {
                    require(cfaPattern == null) { "Non-mosaic sensor evidence cannot declare a CFA pattern" }
                }
            }
            is CameraProcessed -> {
                require(sourceFormat in PROCESSED_FORMATS) {
                    "Camera-processed evidence must use a declared processed public format"
                }
                require(sourceApi != AcquisitionSourceApi.OPAQUE_PRIVATE) {
                    "Camera-processed evidence cannot be an opaque private token"
                }
                require(cfaPattern == null) { "Camera-processed evidence cannot declare a CFA pattern" }
            }
            is OpaqueTransport -> {
                require(sourceFormat == PublicSourceFormat.PRIVATE_TOKEN) {
                    "Opaque transport must remain explicitly typed as a private token"
                }
                require(packing == SamplePacking.OPAQUE) { "Opaque transport must use opaque packing" }
                require(sourceApi == AcquisitionSourceApi.OPAQUE_PRIVATE) {
                    "Opaque transport must retain opaque-private provenance"
                }
                require(cfaPattern == null) { "Opaque transport cannot invent a CFA pattern" }
            }
        }
    }

    private companion object {
        val SENSOR_FORMATS = setOf(
            PublicSourceFormat.RAW_SENSOR,
            PublicSourceFormat.RAW10,
            PublicSourceFormat.RAW12,
            PublicSourceFormat.RAW14,
        )
        val PROCESSED_FORMATS = setOf(
            PublicSourceFormat.YUV_420_888,
            PublicSourceFormat.P010,
            PublicSourceFormat.P210,
        )
    }
}

data class TimebaseEvidence(
    val imageTimestampNs: Long,
    val captureResultTimestampNs: Long?,
    val requestIssuedTimestampNs: Long?,
    val declaredTimebase: AcquisitionTimebase,
    val normalizedOffsetNs: Long?,
    val mappingUncertaintyNs: Long?,
) {
    init {
        require(imageTimestampNs > 0L) { "Image timestamp must be positive" }
        require(captureResultTimestampNs == null || captureResultTimestampNs > 0L) {
            "Capture-result timestamp must be positive when present"
        }
        require(requestIssuedTimestampNs == null || requestIssuedTimestampNs >= 0L) {
            "Request-issued timestamp cannot be negative"
        }
        require(mappingUncertaintyNs == null || mappingUncertaintyNs >= 0L) {
            "Timebase mapping uncertainty cannot be negative"
        }
        require((normalizedOffsetNs == null) == (mappingUncertaintyNs == null)) {
            "Normalized timebase mapping requires both offset and uncertainty"
        }
    }
}

data class AcquisitionIdentity(
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val physicalTarget: PhysicalCameraId?,
    val providerEpoch: Long,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
    val captureToken: CaptureToken,
    val captureGeneration: Long?,
    val surfaceGeneration: Long?,
    val representation: RepresentationDescriptor,
    val timebase: TimebaseEvidence,
) {
    init {
        require(providerEpoch > 0L) { "Camera-provider epoch must be positive" }
        require(captureGeneration == null || captureGeneration >= 0L) {
            "Capture generation cannot be negative"
        }
        require(surfaceGeneration == null || surfaceGeneration >= 0L) {
            "Surface generation cannot be negative"
        }
    }

    fun permitIdentity(): AcquisitionPermitIdentity = AcquisitionPermitIdentity(
        canonicalLensFingerprint = canonicalLensFingerprint,
        cameraProfileFingerprint = cameraProfileFingerprint,
        routeId = routeId,
        physicalTarget = physicalTarget,
        providerEpoch = providerEpoch,
        selectionGeneration = selectionGeneration,
        sessionGeneration = sessionGeneration,
        captureToken = captureToken,
        captureGeneration = captureGeneration,
        surfaceGeneration = surfaceGeneration,
    )
}

data class AcquisitionPermitIdentity(
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val physicalTarget: PhysicalCameraId?,
    val providerEpoch: Long,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
    val captureToken: CaptureToken,
    val captureGeneration: Long?,
    val surfaceGeneration: Long?,
) {
    init {
        require(providerEpoch > 0L) { "Camera-provider epoch must be positive" }
        require(captureGeneration == null || captureGeneration >= 0L)
        require(surfaceGeneration == null || surfaceGeneration >= 0L)
    }
}

private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("Acquisition byte extent overflow", error)
}

private fun checkedMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("Acquisition byte extent overflow", error)
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
