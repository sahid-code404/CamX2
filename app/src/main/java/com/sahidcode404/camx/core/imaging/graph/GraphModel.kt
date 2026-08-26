package com.sahidcode404.camx.core.imaging.graph

import com.sahidcode404.camx.core.camera.acquisition.AcquiredRepresentation
import com.sahidcode404.camx.core.camera.acquisition.CameraProcessed
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterHasher
import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.FullColorSensorSamples
import com.sahidcode404.camx.core.camera.acquisition.InterpretableSensorDomain
import com.sahidcode404.camx.core.camera.acquisition.ManifestSourceId
import com.sahidcode404.camx.core.camera.acquisition.MonochromeSensorSamples
import com.sahidcode404.camx.core.camera.acquisition.MosaicSensorSamples
import com.sahidcode404.camx.core.camera.acquisition.P01010
import com.sahidcode404.camx.core.camera.acquisition.P21010
import com.sahidcode404.camx.core.camera.acquisition.PrivateSurfaceToken
import com.sahidcode404.camx.core.camera.acquisition.RawPrivateToken
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor
import com.sahidcode404.camx.core.camera.acquisition.SamplePacking
import com.sahidcode404.camx.core.camera.acquisition.SensorPixelMode
import com.sahidcode404.camx.core.camera.acquisition.SourceManifestRecord
import com.sahidcode404.camx.core.camera.acquisition.Yuv4208
import com.sahidcode404.camx.core.camera.model.IntSize
import java.util.Collections

object M3GraphLimits {
    const val MAX_NODES = 256
    const val MAX_VALUES = 512
    const val MAX_NODE_INPUTS = 8
    const val MAX_NODE_OUTPUTS = 8
    const val MAX_PARAMETERS_PER_NODE = 64
    const val MAX_PARAMETER_KEY_CHARS = 96
    const val MAX_PARAMETER_VALUE_CHARS = 512
    const val MAX_SOURCE_LINEAGE = 64
    const val MAX_ALGORITHM_ID_CHARS = 128
    const val MAX_VALUE_BYTES = 512L * 1024L * 1024L
    const val MAX_PLAN_RESERVED_BYTES = 8L * 1024L * 1024L * 1024L
}

@JvmInline
value class GraphNodeId(val value: Int) {
    init {
        require(value >= 0) { "Graph node ID cannot be negative" }
    }
}

@JvmInline
value class GraphValueId(val value: Int) {
    init {
        require(value >= 0) { "Graph value ID cannot be negative" }
    }
}

@JvmInline
value class AlgorithmId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= M3GraphLimits.MAX_ALGORITHM_ID_CHARS) {
            "Algorithm ID must be nonblank and bounded"
        }
        require(value.all { it.isLowerCase() || it.isDigit() || it == '.' || it == '-' || it == '_' }) {
            "Algorithm ID must use canonical lowercase ASCII tokens"
        }
    }
}

@JvmInline
value class GraphSha256(val value: String) {
    init {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Graph digest must be lowercase SHA-256"
        }
    }
}

enum class GraphRepresentation {
    SENSOR_MOSAIC,
    SENSOR_MONOCHROME,
    SENSOR_FULL_COLOR,
    CAMERA_P010,
    CAMERA_P210,
    CAMERA_YUV420,
    FUSED_CFA_RADIANCE,
    LINEAR_SCENE_RGB,
}

enum class GraphEncoding {
    UNPACKED_16_LE,
    PACKED_RAW10,
    PACKED_RAW12,
    PACKED_RAW14,
    PLANAR_8,
    PLANAR_10,
}

enum class PhotometricDomain {
    SENSOR_CODE_VALUES,
    CAMERA_PROCESSED_CODE_VALUES,
    LINEAR_SENSOR_RADIANCE,
    LINEAR_SCENE_RGB,
}

enum class GraphMemoryDomain {
    HOST_JVM,
    HOST_NATIVE,
    GPU_DEVICE,
}

enum class GraphValueLifetime {
    EXTERNAL_SOURCE,
    UNTIL_LAST_USE,
    FINAL_OUTPUT,
}

enum class GraphUncertaintySemantics {
    ACQUISITION_EVIDENCE_ONLY,
    RADIOMETRIC_VARIANCE,
    VISIBILITY_AND_SUPPORT,
    FULL_RECONSTRUCTION_UNCERTAINTY,
}

enum class TemporalScope {
    SINGLE_CAPTURE,
    FRAME_SET,
    TEMPORAL_SEQUENCE,
}

enum class GraphBackend {
    SCALAR_REFERENCE,
}

enum class DeterminismClass {
    BIT_EXACT,
}

enum class GraphLatencyClass {
    DEFERRED_REFERENCE,
}

data class GraphCalibrationIdentity(
    val identity: String?,
    val version: String?,
    val confidence: Double,
    val colorIdentity: String?,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Graph calibration confidence must be finite and in [0, 1]"
        }
        require((identity == null) == (version == null)) {
            "Graph calibration identity and version must be paired"
        }
        require(identity == null || identity.isNotBlank()) { "Graph calibration identity cannot be blank" }
        require(version == null || version.isNotBlank()) { "Graph calibration version cannot be blank" }
        require(colorIdentity == null || colorIdentity.isNotBlank()) {
            "Graph color calibration identity cannot be blank"
        }
        require(confidence == 0.0 || identity != null) {
            "Non-zero graph calibration confidence requires an identity"
        }
    }
}

class SourceLineage(
    sourceIds: List<ManifestSourceId>,
    val temporalScope: TemporalScope,
) {
    val sourceIds: List<ManifestSourceId> = immutableList(sourceIds.sortedBy { it.value })

    init {
        require(sourceIds.isNotEmpty() && sourceIds.size <= M3GraphLimits.MAX_SOURCE_LINEAGE) {
            "Source lineage must be non-empty and bounded"
        }
        require(this.sourceIds.distinct().size == this.sourceIds.size) {
            "Source lineage cannot contain duplicate source IDs"
        }
        if (temporalScope == TemporalScope.SINGLE_CAPTURE) {
            require(this.sourceIds.size == 1) { "Single-capture lineage requires exactly one source" }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SourceLineage && sourceIds == other.sourceIds && temporalScope == other.temporalScope

    override fun hashCode(): Int = 31 * sourceIds.hashCode() + temporalScope.hashCode()

    override fun toString(): String = "SourceLineage(sourceIds=$sourceIds, temporalScope=$temporalScope)"
}

data class CanonicalGraphPlaneLayout(
    val planeIndex: Int,
    val offsetBytes: Long,
    val rowStrideBytes: Long,
    val meaningfulRowBytes: Long,
    val rowCount: Int,
    val pixelStrideBytes: Int,
) {
    init {
        require(planeIndex >= 0) { "Graph plane index cannot be negative" }
        require(offsetBytes >= 0L) { "Graph plane offset cannot be negative" }
        require(rowStrideBytes > 0L && meaningfulRowBytes > 0L) { "Graph row extents must be positive" }
        require(rowStrideBytes == meaningfulRowBytes) {
            "Canonical graph rasters cannot retain undefined row padding"
        }
        require(rowCount > 0) { "Graph plane row count must be positive" }
        require(pixelStrideBytes > 0) { "Graph plane pixel stride must be positive" }
    }

    fun byteCount(): Long = checkedMultiply(meaningfulRowBytes, rowCount.toLong(), "Graph plane byte-count overflow")
}

class CanonicalGraphLayout(planes: List<CanonicalGraphPlaneLayout>) {
    val planes: List<CanonicalGraphPlaneLayout> = immutableList(planes.sortedBy { it.planeIndex })
    val byteCount: Long

    init {
        require(this.planes.isNotEmpty()) { "Canonical graph layout requires at least one plane" }
        require(this.planes.indices.all { this.planes[it].planeIndex == it }) {
            "Canonical graph plane indices must be contiguous from zero"
        }
        var expectedOffset = 0L
        this.planes.forEach { plane ->
            require(plane.offsetBytes == expectedOffset) {
                "Canonical graph planes must be tightly concatenated without gaps"
            }
            expectedOffset = checkedAdd(expectedOffset, plane.byteCount(), "Graph layout byte-count overflow")
        }
        require(expectedOffset in 1..M3GraphLimits.MAX_VALUE_BYTES) {
            "Graph value byte count exceeds the M3 bound"
        }
        byteCount = expectedOffset
    }

    override fun equals(other: Any?): Boolean = other is CanonicalGraphLayout && planes == other.planes

    override fun hashCode(): Int = planes.hashCode()

    override fun toString(): String = "CanonicalGraphLayout(planes=$planes)"

    companion object {
        fun from(descriptor: RepresentationDescriptor): CanonicalGraphLayout {
            var offset = 0L
            val graphPlanes = descriptor.planes.map { plane ->
                val result = CanonicalGraphPlaneLayout(
                    planeIndex = plane.planeIndex,
                    offsetBytes = offset,
                    rowStrideBytes = plane.meaningfulRowBytes,
                    meaningfulRowBytes = plane.meaningfulRowBytes,
                    rowCount = plane.rowCount,
                    pixelStrideBytes = plane.pixelStrideBytes,
                )
                offset = checkedAdd(offset, plane.canonicalByteCount(), "Graph layout offset overflow")
                result
            }
            return CanonicalGraphLayout(graphPlanes)
        }
    }
}

data class GraphValueType(
    val representation: GraphRepresentation,
    val encoding: GraphEncoding,
    val photometricDomain: PhotometricDomain,
    val size: IntSize,
    val validAreaLeft: Int,
    val validAreaTop: Int,
    val validAreaWidth: Int,
    val validAreaHeight: Int,
    val layout: CanonicalGraphLayout,
    val storedBits: Int,
    val effectiveBits: Int,
    val cfaPattern: CfaPattern?,
    val sensorPixelMode: SensorPixelMode,
    val calibration: GraphCalibrationIdentity,
    val lineage: SourceLineage,
    val uncertaintySemantics: GraphUncertaintySemantics,
) {
    init {
        require(storedBits in 1..32) { "Graph stored precision must be in 1..32" }
        require(effectiveBits in 1..storedBits) { "Graph effective precision must be in 1..storedBits" }
        require(validAreaLeft >= 0 && validAreaTop >= 0 && validAreaWidth > 0 && validAreaHeight > 0) {
            "Graph valid area must be positive and non-negative"
        }
        require(validAreaLeft.toLong() + validAreaWidth.toLong() <= size.width.toLong() &&
            validAreaTop.toLong() + validAreaHeight.toLong() <= size.height.toLong()
        ) { "Graph valid area must lie inside dimensions" }
        when (representation) {
            GraphRepresentation.SENSOR_MOSAIC,
            GraphRepresentation.FUSED_CFA_RADIANCE,
            -> require(cfaPattern != null) { "CFA graph representations require a CFA pattern" }

            GraphRepresentation.SENSOR_MONOCHROME,
            GraphRepresentation.SENSOR_FULL_COLOR,
            GraphRepresentation.CAMERA_P010,
            GraphRepresentation.CAMERA_P210,
            GraphRepresentation.CAMERA_YUV420,
            GraphRepresentation.LINEAR_SCENE_RGB,
            -> require(cfaPattern == null) { "Non-CFA graph representations cannot declare a CFA pattern" }
        }
        when (photometricDomain) {
            PhotometricDomain.SENSOR_CODE_VALUES -> require(
                representation == GraphRepresentation.SENSOR_MOSAIC ||
                    representation == GraphRepresentation.SENSOR_MONOCHROME ||
                    representation == GraphRepresentation.SENSOR_FULL_COLOR,
            ) { "Sensor code values require a sensor-domain representation" }

            PhotometricDomain.CAMERA_PROCESSED_CODE_VALUES -> require(
                representation == GraphRepresentation.CAMERA_P010 ||
                    representation == GraphRepresentation.CAMERA_P210 ||
                    representation == GraphRepresentation.CAMERA_YUV420,
            ) { "Camera-processed code values require processed-source representation" }

            PhotometricDomain.LINEAR_SENSOR_RADIANCE -> require(
                representation == GraphRepresentation.FUSED_CFA_RADIANCE,
            ) { "Linear sensor radiance requires FusedCfaRadiance semantics" }

            PhotometricDomain.LINEAR_SCENE_RGB -> require(
                representation == GraphRepresentation.LINEAR_SCENE_RGB,
            ) { "Linear scene RGB domain requires LinearSceneRgb semantics" }
        }
    }

    companion object {
        fun from(record: SourceManifestRecord): GraphValueType {
            val descriptor = record.identity.representation
            require(descriptor.representation !is com.sahidcode404.camx.core.camera.acquisition.OpaqueTransport) {
                "Opaque transport cannot enter the typed graph"
            }
            val representation = graphRepresentation(descriptor.representation)
            return GraphValueType(
                representation = representation,
                encoding = graphEncoding(descriptor.packing),
                photometricDomain = if (descriptor.representation is InterpretableSensorDomain) {
                    PhotometricDomain.SENSOR_CODE_VALUES
                } else {
                    PhotometricDomain.CAMERA_PROCESSED_CODE_VALUES
                },
                size = descriptor.size,
                validAreaLeft = descriptor.activeArea.left,
                validAreaTop = descriptor.activeArea.top,
                validAreaWidth = descriptor.activeArea.width,
                validAreaHeight = descriptor.activeArea.height,
                layout = CanonicalGraphLayout.from(descriptor),
                storedBits = descriptor.storedBits,
                effectiveBits = descriptor.effectiveBits,
                cfaPattern = descriptor.cfaPattern,
                sensorPixelMode = descriptor.sensorPixelMode,
                calibration = GraphCalibrationIdentity(
                    identity = descriptor.calibration.identity,
                    version = descriptor.calibration.version,
                    confidence = descriptor.calibration.confidence,
                    colorIdentity = descriptor.colorCalibrationIdentity,
                ),
                lineage = SourceLineage(listOf(record.sourceId), TemporalScope.SINGLE_CAPTURE),
                uncertaintySemantics = GraphUncertaintySemantics.ACQUISITION_EVIDENCE_ONLY,
            )
        }
    }
}

data class GraphSourceBinding(
    val sourceId: ManifestSourceId,
    val canonicalRasterSha256: String,
    val representationDescriptorSha256: String,
) {
    init {
        require(isLowerSha256(canonicalRasterSha256)) { "Source raster digest must be lowercase SHA-256" }
        require(isLowerSha256(representationDescriptorSha256)) {
            "Source representation digest must be lowercase SHA-256"
        }
    }
}

class GraphValue(
    val id: GraphValueId,
    val type: GraphValueType,
    val canonicalBytes: Long,
    val sourceBinding: GraphSourceBinding?,
    val memoryDomain: GraphMemoryDomain,
    val lifetime: GraphValueLifetime,
) {
    init {
        require(canonicalBytes == type.layout.byteCount) {
            "Graph value byte count must match its canonical layout"
        }
        require(canonicalBytes in 1..M3GraphLimits.MAX_VALUE_BYTES) {
            "Graph value byte count exceeds M3 limits"
        }
        require((sourceBinding != null) == (lifetime == GraphValueLifetime.EXTERNAL_SOURCE)) {
            "Only external source values may carry source bindings"
        }
    }

    companion object {
        fun source(id: GraphValueId, record: SourceManifestRecord): GraphValue = GraphValue(
            id = id,
            type = GraphValueType.from(record),
            canonicalBytes = record.canonicalRaster.byteCount,
            sourceBinding = GraphSourceBinding(
                sourceId = record.sourceId,
                canonicalRasterSha256 = record.canonicalRaster.sha256,
                representationDescriptorSha256 = record.descriptorSha256,
            ),
            memoryDomain = GraphMemoryDomain.HOST_JVM,
            lifetime = GraphValueLifetime.EXTERNAL_SOURCE,
        )

        fun intermediate(
            id: GraphValueId,
            type: GraphValueType,
            lifetime: GraphValueLifetime = GraphValueLifetime.UNTIL_LAST_USE,
        ): GraphValue {
            require(lifetime != GraphValueLifetime.EXTERNAL_SOURCE) {
                "Intermediate graph values cannot masquerade as external sources"
            }
            return GraphValue(
                id = id,
                type = type,
                canonicalBytes = type.layout.byteCount,
                sourceBinding = null,
                memoryDomain = GraphMemoryDomain.HOST_JVM,
                lifetime = lifetime,
            )
        }
    }
}

data class NodeParameter(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank() && key.length <= M3GraphLimits.MAX_PARAMETER_KEY_CHARS) {
            "Node parameter key must be nonblank and bounded"
        }
        require(value.length <= M3GraphLimits.MAX_PARAMETER_VALUE_CHARS) {
            "Node parameter value exceeds the M3 bound"
        }
    }
}

class GraphNodeInvocation(
    val id: GraphNodeId,
    val algorithmId: AlgorithmId,
    val algorithmVersion: Int,
    val parameterSchemaVersion: Int,
    inputs: List<GraphValueId>,
    outputs: List<GraphValueId>,
    parameters: List<NodeParameter> = emptyList(),
) {
    val inputs: List<GraphValueId> = immutableList(inputs)
    val outputs: List<GraphValueId> = immutableList(outputs)
    val parameters: List<NodeParameter> = immutableList(
        parameters.sortedWith(compareBy(NodeParameter::key, NodeParameter::value)),
    )

    init {
        require(algorithmVersion > 0) { "Algorithm version must be positive" }
        require(parameterSchemaVersion > 0) { "Parameter schema version must be positive" }
    }
}

class GraphDefinition(
    values: List<GraphValue>,
    nodes: List<GraphNodeInvocation>,
    finalOutputs: List<GraphValueId>,
) {
    val values: List<GraphValue> = immutableList(values.sortedBy { it.id.value })
    val nodes: List<GraphNodeInvocation> = immutableList(nodes.sortedBy { it.id.value })
    val finalOutputs: List<GraphValueId> = immutableList(finalOutputs.sortedBy { it.value })
}

data class GraphResourceBudget(
    val maxResidentBytes: Long,
    val maxWorkspaceBytes: Long,
    val safetyMarginBytes: Long,
    val maxNodes: Int = M3GraphLimits.MAX_NODES,
    val maxValues: Int = M3GraphLimits.MAX_VALUES,
) {
    init {
        require(maxResidentBytes in 1..M3GraphLimits.MAX_PLAN_RESERVED_BYTES) {
            "Graph resident budget is outside M3 limits"
        }
        require(maxWorkspaceBytes in 0..maxResidentBytes) { "Graph workspace budget is invalid" }
        require(safetyMarginBytes in 0..maxResidentBytes) { "Graph safety margin is invalid" }
        require(maxNodes in 1..M3GraphLimits.MAX_NODES) { "Graph node budget is outside M3 limits" }
        require(maxValues in 1..M3GraphLimits.MAX_VALUES) { "Graph value budget is outside M3 limits" }
    }
}

data class MutationFlags(
    val changesSamples: Boolean,
    val changesGeometry: Boolean,
    val changesRepresentation: Boolean,
    val changesProvenance: Boolean,
)

enum class GraphCompileFailureReason {
    LIMIT_EXCEEDED,
    DUPLICATE_NODE,
    DUPLICATE_VALUE,
    MISSING_VALUE,
    DUPLICATE_PRODUCER,
    SOURCE_REWRITE,
    UNPRODUCED_VALUE,
    DANGLING_VALUE,
    CYCLE,
    UNKNOWN_ALGORITHM,
    MALFORMED_METADATA,
    TYPE_MISMATCH,
    CALIBRATION_INSUFFICIENT,
    UNSUPPORTED_BACKEND,
    RESOURCE_BUDGET,
}

class GraphCompilationException(
    val reason: GraphCompileFailureReason,
    message: String,
) : IllegalArgumentException(message)

internal fun descriptorSha256(descriptor: RepresentationDescriptor): String =
    CanonicalRasterHasher.descriptorSha256(descriptor)

private fun graphRepresentation(representation: AcquiredRepresentation): GraphRepresentation = when (representation) {
    MosaicSensorSamples -> GraphRepresentation.SENSOR_MOSAIC
    MonochromeSensorSamples -> GraphRepresentation.SENSOR_MONOCHROME
    FullColorSensorSamples -> GraphRepresentation.SENSOR_FULL_COLOR
    P01010 -> GraphRepresentation.CAMERA_P010
    P21010 -> GraphRepresentation.CAMERA_P210
    Yuv4208 -> GraphRepresentation.CAMERA_YUV420
    RawPrivateToken,
    PrivateSurfaceToken,
    -> throw IllegalArgumentException("Opaque transport cannot be represented in the graph")

    else -> when (representation) {
        is InterpretableSensorDomain -> throw IllegalArgumentException("Unknown sensor representation")
        is CameraProcessed -> throw IllegalArgumentException("Unknown processed representation")
        else -> throw IllegalArgumentException("Unknown acquired representation")
    }
}

private fun graphEncoding(packing: SamplePacking): GraphEncoding = when (packing) {
    SamplePacking.UNPACKED_16_LE -> GraphEncoding.UNPACKED_16_LE
    SamplePacking.PACKED_RAW10 -> GraphEncoding.PACKED_RAW10
    SamplePacking.PACKED_RAW12 -> GraphEncoding.PACKED_RAW12
    SamplePacking.PACKED_RAW14 -> GraphEncoding.PACKED_RAW14
    SamplePacking.PLANAR_8 -> GraphEncoding.PLANAR_8
    SamplePacking.PLANAR_10 -> GraphEncoding.PLANAR_10
    SamplePacking.OPAQUE -> throw IllegalArgumentException("Opaque packing cannot enter the graph")
}

private fun isLowerSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

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

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
