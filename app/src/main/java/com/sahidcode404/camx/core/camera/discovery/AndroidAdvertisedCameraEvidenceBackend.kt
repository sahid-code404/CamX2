package com.sahidcode404.camx.core.camera.discovery

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.security.MessageDigest
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

const val AUX_MAX_PUBLIC_IDS = 64
const val AUX_MAX_PHYSICAL_IDS_PER_LOGICAL = 64
const val AUX_MAX_FOCAL_LENGTHS = 16
const val AUX_MAX_APERTURES = 16
const val AUX_MAX_PREVIEW_STREAMS = 128
const val AUX_MAX_FPS_RANGES = 64
const val AUX_MAX_RAW_SIZES = 64

internal data class JavaAdvertisedCameraRecord(
    val queriedId: String,
    val facing: LensFacing,
    val focalLengthsMillimetres: List<Float>,
    val sensorPhysicalWidthMillimetres: Float?,
    val sensorPhysicalHeightMillimetres: Float?,
    val activeArray: IntSize?,
    val pixelArray: IntSize?,
    val sensorOrientationDegrees: Int?,
    val apertureValues: List<Float>,
    val colorFilterArrangement: Int?,
    val capabilities: CameraCapabilities,
    val physicalIds: List<String>,
)

internal interface JavaAdvertisedCameraMetadataSource {
    fun advertisedIds(): List<String>
    fun read(id: String): JavaAdvertisedCameraRecord?
}

enum class JavaAdvertisedEvidenceFailureKind {
    ID_ENUMERATION_UNAVAILABLE,
    PUBLIC_ID_LIMIT_EXCEEDED,
    INVALID_PUBLIC_ID,
    CHARACTERISTICS_UNAVAILABLE,
    PHYSICAL_ID_LIMIT_EXCEEDED,
    INVALID_PHYSICAL_ID,
    PHYSICAL_CHARACTERISTICS_UNAVAILABLE,
    METADATA_BOUND_EXCEEDED,
}

data class JavaAdvertisedEvidenceFailure(
    val kind: JavaAdvertisedEvidenceFailureKind,
    val transportId: String? = null,
    val physicalId: String? = null,
)

data class JavaAdvertisedEvidenceReport(
    val snapshots: List<CameraEvidenceSnapshot>,
    val failures: List<JavaAdvertisedEvidenceFailure>,
) {
    fun snapshotFor(source: CameraRouteSource): CameraEvidenceSnapshot? =
        snapshots.firstOrNull { it.source == source }
}

/**
 * Bounded public Camera2 metadata collector for CAMX-107.
 *
 * The frozen CAMX-102 STARTUP_SEED path remains separate and fast. This backend performs no work at
 * STARTUP_SEED depth. ADVERTISED/DEEP collect immutable metadata only and never acquire a camera.
 */
internal class AndroidAdvertisedCameraEvidenceBackend(
    private val environment: CameraEnvironmentFingerprint,
    private val clockNanos: () -> Long,
    private val source: JavaAdvertisedCameraMetadataSource,
) : CameraEvidenceBackend {
    constructor(
        cameraManager: CameraManager,
        environment: CameraEnvironmentFingerprint,
        clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    ) : this(
        environment = environment,
        clockNanos = clockNanos,
        source = AndroidJavaAdvertisedCameraMetadataSource(cameraManager),
    )

    override suspend fun discover(depth: DiscoveryDepth): CameraEvidenceSnapshot =
        discoverReport(depth).snapshotFor(CameraRouteSource.JAVA_PUBLIC)
            ?: emptySnapshot(CameraRouteSource.JAVA_PUBLIC)

    suspend fun discoverReport(depth: DiscoveryDepth): JavaAdvertisedEvidenceReport {
        if (depth == DiscoveryDepth.STARTUP_SEED) {
            return JavaAdvertisedEvidenceReport(
                snapshots = immutableList(listOf(emptySnapshot(CameraRouteSource.JAVA_PUBLIC))),
                failures = emptyList(),
            )
        }
        coroutineContext.ensureActive()
        val failures = ArrayList<JavaAdvertisedEvidenceFailure>()
        val ids = try {
            source.advertisedIds().toList()
        } catch (_: Exception) {
            failures += JavaAdvertisedEvidenceFailure(JavaAdvertisedEvidenceFailureKind.ID_ENUMERATION_UNAVAILABLE)
            return report(emptyList(), emptyList(), failures)
        }
        if (ids.size > AUX_MAX_PUBLIC_IDS) {
            failures += JavaAdvertisedEvidenceFailure(JavaAdvertisedEvidenceFailureKind.PUBLIC_ID_LIMIT_EXCEEDED)
            return report(emptyList(), emptyList(), failures)
        }

        val publicEvidence = ArrayList<CameraMetadataEvidence>()
        val physicalEvidence = ArrayList<CameraMetadataEvidence>()
        for (rawId in ids.asSequence().distinct().sortedWith(::opaqueCompare)) {
            coroutineContext.ensureActive()
            if (rawId.isBlank()) {
                failures += JavaAdvertisedEvidenceFailure(
                    JavaAdvertisedEvidenceFailureKind.INVALID_PUBLIC_ID,
                    transportId = rawId,
                )
                continue
            }
            val publicRecord = readBounded(rawId, failures, physical = false) ?: continue
            val parentId = CameraTransportId(rawId)
            publicEvidence += publicRecord.toEvidence(CameraRouteSource.JAVA_PUBLIC, parentId)

            if (publicRecord.physicalIds.size > AUX_MAX_PHYSICAL_IDS_PER_LOGICAL) {
                failures += JavaAdvertisedEvidenceFailure(
                    JavaAdvertisedEvidenceFailureKind.PHYSICAL_ID_LIMIT_EXCEEDED,
                    transportId = rawId,
                )
                continue
            }
            for (member in publicRecord.physicalIds.asSequence().distinct().sortedWith(::opaqueCompare)) {
                coroutineContext.ensureActive()
                if (member.isBlank()) {
                    failures += JavaAdvertisedEvidenceFailure(
                        JavaAdvertisedEvidenceFailureKind.INVALID_PHYSICAL_ID,
                        transportId = rawId,
                        physicalId = member,
                    )
                    continue
                }
                val physical = readBounded(member, failures, physical = true)
                val physicalId = PhysicalCameraId(member)
                physicalEvidence += physical?.toEvidence(
                    source = CameraRouteSource.JAVA_PHYSICAL,
                    transportId = parentId,
                    physicalId = physicalId,
                    logicalParentId = parentId,
                ) ?: CameraMetadataEvidence(
                    source = CameraRouteSource.JAVA_PHYSICAL,
                    transportId = parentId,
                    physicalId = physicalId,
                    logicalParentId = parentId,
                    facing = publicRecord.facing,
                )
            }
        }
        return report(
            publicEvidence.sortedBy(::evidenceKey),
            physicalEvidence.sortedBy(::evidenceKey),
            failures,
        )
    }

    private fun readBounded(
        id: String,
        failures: MutableList<JavaAdvertisedEvidenceFailure>,
        physical: Boolean,
    ): JavaAdvertisedCameraRecord? {
        val record = try {
            source.read(id)
        } catch (_: Exception) {
            null
        }
        if (record == null) {
            failures += JavaAdvertisedEvidenceFailure(
                if (physical) JavaAdvertisedEvidenceFailureKind.PHYSICAL_CHARACTERISTICS_UNAVAILABLE
                else JavaAdvertisedEvidenceFailureKind.CHARACTERISTICS_UNAVAILABLE,
                transportId = if (physical) null else id,
                physicalId = if (physical) id else null,
            )
            return null
        }
        if (record.queriedId != id ||
            record.focalLengthsMillimetres.size > AUX_MAX_FOCAL_LENGTHS ||
            record.apertureValues.size > AUX_MAX_APERTURES ||
            record.capabilities.previewStreams.size > AUX_MAX_PREVIEW_STREAMS ||
            record.capabilities.fpsRanges.size > AUX_MAX_FPS_RANGES ||
            record.capabilities.rawSizes.size > AUX_MAX_RAW_SIZES
        ) {
            failures += JavaAdvertisedEvidenceFailure(
                JavaAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED,
                transportId = if (physical) null else id,
                physicalId = if (physical) id else null,
            )
            return null
        }
        return record
    }

    private fun report(
        publicEvidence: List<CameraMetadataEvidence>,
        physicalEvidence: List<CameraMetadataEvidence>,
        failures: List<JavaAdvertisedEvidenceFailure>,
    ): JavaAdvertisedEvidenceReport {
        val completed = clockNanos().coerceAtLeast(0L)
        val snapshots = ArrayList<CameraEvidenceSnapshot>(2)
        snapshots += CameraEvidenceSnapshot(
            source = CameraRouteSource.JAVA_PUBLIC,
            environment = environment,
            evidence = immutableList(publicEvidence),
            completedAtElapsedRealtimeNs = completed,
        )
        if (physicalEvidence.isNotEmpty()) {
            snapshots += CameraEvidenceSnapshot(
                source = CameraRouteSource.JAVA_PHYSICAL,
                environment = environment,
                evidence = immutableList(physicalEvidence),
                completedAtElapsedRealtimeNs = completed,
            )
        }
        return JavaAdvertisedEvidenceReport(immutableList(snapshots), immutableList(failures))
    }

    private fun emptySnapshot(source: CameraRouteSource) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = emptyList(),
        completedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
    )

    private fun JavaAdvertisedCameraRecord.toEvidence(
        source: CameraRouteSource,
        transportId: CameraTransportId,
        physicalId: PhysicalCameraId? = null,
        logicalParentId: CameraTransportId? = null,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = transportId,
        physicalId = physicalId,
        logicalParentId = logicalParentId,
        facing = facing,
        focalLengthsMillimetres = immutableList(focalLengthsMillimetres),
        sensorPhysicalWidthMillimetres = sensorPhysicalWidthMillimetres,
        sensorPhysicalHeightMillimetres = sensorPhysicalHeightMillimetres,
        activeArray = activeArray,
        pixelArray = pixelArray,
        sensorOrientationDegrees = sensorOrientationDegrees,
        apertureValues = immutableList(apertureValues),
        colorFilterArrangement = colorFilterArrangement,
        capabilities = capabilities.copy(
            previewStreams = immutableList(capabilities.previewStreams),
            fpsRanges = immutableList(capabilities.fpsRanges),
            rawSizes = immutableList(capabilities.rawSizes),
        ),
    )

    private companion object {
        fun opaqueCompare(left: String, right: String): Int = stableOpaqueKey(left).compareTo(stableOpaqueKey(right))

        fun stableOpaqueKey(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun evidenceKey(value: CameraMetadataEvidence): String = buildString {
            append(stableOpaqueKey(value.transportId.value))
            append('|').append(value.physicalId?.value?.let(::stableOpaqueKey).orEmpty())
            append('|').append(value.source.ordinal)
        }

        fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}

internal class AndroidJavaAdvertisedCameraMetadataSource(
    private val cameraManager: CameraManager,
) : JavaAdvertisedCameraMetadataSource {
    override fun advertisedIds(): List<String> = cameraManager.cameraIdList.toList()

    override fun read(id: String): JavaAdvertisedCameraRecord {
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val advertised = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawAdvertised = advertised.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val logicalAdvertised = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            advertised.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

        val previewSizes: Array<Size> = map?.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
        val previewStreams = previewSizes.asSequence()
            .take(AUX_MAX_PREVIEW_STREAMS + 1)
            .filter { it.width > 0 && it.height > 0 }
            .map { size ->
                val duration = runCatching {
                    map?.getOutputMinFrameDuration(SurfaceHolder::class.java, size) ?: 0L
                }.getOrDefault(0L)
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(size.width, size.height),
                    duration.takeIf { it > 0L },
                )
            }
            .distinct()
            .sortedWith(compareBy({ it.size.area }, { it.size.width }, { it.size.height }))
            .toList()

        val ranges: Array<Range<Int>> =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fpsRanges = ranges.asSequence()
            .take(AUX_MAX_FPS_RANGES + 1)
            .filter { it.lower > 0 && it.upper >= it.lower }
            .map { CameraFpsCapability(it.lower, it.upper) }
            .distinct()
            .sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .toList()

        val rawSizes = if (rawAdvertised) {
            val sizes: Array<Size> = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            sizes.asSequence()
                .take(AUX_MAX_RAW_SIZES + 1)
                .filter { it.width > 0 && it.height > 0 }
                .map { IntSize(it.width, it.height) }
                .distinct()
                .sortedWith(compareBy({ it.area }, { it.width }, { it.height }))
                .toList()
        } else emptyList()

        val focalArray = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val focalLengths = focalArray.asSequence()
            .filter { it.isFinite() && it > 0f }.distinct().sorted()
            .take(AUX_MAX_FOCAL_LENGTHS + 1).toList()
        val apertureArray = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
        val apertures = apertureArray.asSequence()
            .filter { it.isFinite() && it > 0f }.distinct().sorted()
            .take(AUX_MAX_APERTURES + 1).toList()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
        val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?.takeIf { it in 0..270 && it % 90 == 0 }
        val physicalIds = if (logicalAdvertised) {
            characteristics.physicalCameraIds.asSequence()
                .take(AUX_MAX_PHYSICAL_IDS_PER_LOGICAL + 1)
                .toList()
        } else emptyList()

        return JavaAdvertisedCameraRecord(
            queriedId = id,
            facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            },
            focalLengthsMillimetres = immutableList(focalLengths),
            sensorPhysicalWidthMillimetres = physicalSize?.width,
            sensorPhysicalHeightMillimetres = physicalSize?.height,
            activeArray = activeRect?.takeIf { it.width() > 0 && it.height() > 0 }
                ?.let { IntSize(it.width(), it.height()) },
            pixelArray = pixelSize?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { IntSize(it.width, it.height) },
            sensorOrientationDegrees = orientation,
            apertureValues = immutableList(apertures),
            colorFilterArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            capabilities = CameraCapabilities(
                previewStreams = immutableList(previewStreams),
                fpsRanges = immutableList(fpsRanges),
                rawSizes = immutableList(rawSizes),
            ),
            physicalIds = immutableList(physicalIds),
        )
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
