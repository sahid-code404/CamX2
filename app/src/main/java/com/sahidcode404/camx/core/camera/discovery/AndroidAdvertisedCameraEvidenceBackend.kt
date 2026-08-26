package com.sahidcode404.camx.core.camera.discovery

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import com.sahidcode404.camx.core.camera.concurrency.boundedCameraMap
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
const val AUX_MAX_TOTAL_PHYSICAL_RELATIONSHIPS = 64
const val AUX_MAX_ENRICHMENT_TARGETS = 128
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
) {
    fun minimalCopy(): JavaAdvertisedCameraRecord = copy(
        apertureValues = emptyList(),
        colorFilterArrangement = null,
        capabilities = CameraCapabilities(previewStreams = capabilities.previewStreams),
    )
}

internal interface JavaAdvertisedCameraMetadataSource {
    fun advertisedIds(): List<String>
    suspend fun read(id: String): JavaAdvertisedCameraRecord?
    suspend fun readMinimal(id: String): JavaAdvertisedCameraRecord? = read(id)?.minimalCopy()
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
 * Bounded public Camera2 metadata collector. Minimal evidence is published in chunks before
 * capability enrichment, and logical/physical relationships are published before child metadata
 * is queried. The frozen startup-seed path remains separate.
 */
internal class AndroidAdvertisedCameraEvidenceBackend(
    private val environment: CameraEnvironmentFingerprint,
    private val clockNanos: () -> Long,
    private val source: JavaAdvertisedCameraMetadataSource,
    private val metadataBudget: DiscoveryMetadataBudget = DiscoveryMetadataBudget(),
) : CameraEvidenceBackend {
    constructor(
        cameraManager: CameraManager,
        environment: CameraEnvironmentFingerprint,
        clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
        metadataBudget: DiscoveryMetadataBudget = DiscoveryMetadataBudget(),
    ) : this(
        environment = environment,
        clockNanos = clockNanos,
        source = AndroidJavaAdvertisedCameraMetadataSource(cameraManager),
        metadataBudget = metadataBudget,
    )

    override suspend fun discover(depth: DiscoveryDepth): CameraEvidenceSnapshot =
        discoverReport(depth).snapshotFor(CameraRouteSource.JAVA_PUBLIC)
            ?: emptySnapshot(CameraRouteSource.JAVA_PUBLIC)

    suspend fun discoverReport(depth: DiscoveryDepth): JavaAdvertisedEvidenceReport =
        discoverIncrementally(depth) {}

    suspend fun discoverIncrementally(
        depth: DiscoveryDepth,
        emit: suspend (JavaAdvertisedEvidenceReport) -> Unit,
    ): JavaAdvertisedEvidenceReport {
        if (depth == DiscoveryDepth.STARTUP_SEED) {
            return JavaAdvertisedEvidenceReport(
                snapshots = immutableList(listOf(emptySnapshot(CameraRouteSource.JAVA_PUBLIC))),
                failures = emptyList(),
            )
        }
        coroutineContext.ensureActive()
        val failures = ArrayList<JavaAdvertisedEvidenceFailure>()
        val ids = try {
            metadataBudget.withJavaMetadata { source.advertisedIds().toList() }
        } catch (_: Exception) {
            failures += JavaAdvertisedEvidenceFailure(JavaAdvertisedEvidenceFailureKind.ID_ENUMERATION_UNAVAILABLE)
            return report(emptyList(), emptyList(), failures)
        }
        if (ids.size > AUX_MAX_PUBLIC_IDS) {
            failures += JavaAdvertisedEvidenceFailure(JavaAdvertisedEvidenceFailureKind.PUBLIC_ID_LIMIT_EXCEEDED)
            return report(emptyList(), emptyList(), failures)
        }

        val finalEvidence = LinkedHashMap<EvidenceAddress, CameraMetadataEvidence>()
        val enrichmentTargets = ArrayList<EnrichmentTarget>()
        var physicalRelationshipCount = 0
        val orderedIds = ids.distinct().sortedWith(::opaqueCompare)

        for (chunk in orderedIds.chunked(metadataBudget.javaLanes)) {
            coroutineContext.ensureActive()
            val results = boundedCameraMap(chunk, metadataBudget.javaLanes) { rawId ->
                if (rawId.isBlank()) {
                    MinimalReadResult(
                        rawId,
                        null,
                        JavaAdvertisedEvidenceFailure(
                            JavaAdvertisedEvidenceFailureKind.INVALID_PUBLIC_ID,
                            transportId = rawId,
                        ),
                    )
                } else {
                    val read = readBounded(rawId, physical = false, minimal = true)
                    MinimalReadResult(rawId, read.record, read.failure)
                }
            }
            val publicBatch = ArrayList<CameraMetadataEvidence>()
            val physicalBatch = ArrayList<CameraMetadataEvidence>()
            for (result in results) {
                result.failure?.let(failures::add)
                val record = result.record ?: continue
                val parentId = CameraTransportId(result.id)
                val public = record.toEvidence(CameraRouteSource.JAVA_PUBLIC, parentId)
                publicBatch += public
                finalEvidence[public.address()] = public
                if (enrichmentTargets.size < AUX_MAX_ENRICHMENT_TARGETS) {
                    enrichmentTargets += EnrichmentTarget.public(result.id)
                }

                if (record.physicalIds.size > AUX_MAX_PHYSICAL_IDS_PER_LOGICAL) {
                    failures += JavaAdvertisedEvidenceFailure(
                        JavaAdvertisedEvidenceFailureKind.PHYSICAL_ID_LIMIT_EXCEEDED,
                        transportId = result.id,
                    )
                    continue
                }
                for (member in record.physicalIds.distinct().sortedWith(::opaqueCompare)) {
                    if (member.isBlank()) {
                        failures += JavaAdvertisedEvidenceFailure(
                            JavaAdvertisedEvidenceFailureKind.INVALID_PHYSICAL_ID,
                            transportId = result.id,
                            physicalId = member,
                        )
                        continue
                    }
                    if (physicalRelationshipCount >= AUX_MAX_TOTAL_PHYSICAL_RELATIONSHIPS ||
                        enrichmentTargets.size >= AUX_MAX_ENRICHMENT_TARGETS
                    ) {
                        failures += JavaAdvertisedEvidenceFailure(
                            JavaAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED,
                            transportId = result.id,
                            physicalId = member,
                        )
                        continue
                    }
                    physicalRelationshipCount += 1
                    val physicalId = PhysicalCameraId(member)
                    val sparse = CameraMetadataEvidence(
                        source = CameraRouteSource.JAVA_PHYSICAL,
                        transportId = parentId,
                        physicalId = physicalId,
                        logicalParentId = parentId,
                        facing = record.facing,
                    )
                    physicalBatch += sparse
                    finalEvidence[sparse.address()] = sparse
                    enrichmentTargets += EnrichmentTarget.physical(result.id, member)
                }
            }
            emitBatch(publicBatch, physicalBatch, emit)
        }

        // Less urgent metadata comes only after Stage-A candidate/relationship publication.
        for (chunk in enrichmentTargets.chunked(metadataBudget.javaLanes)) {
            coroutineContext.ensureActive()
            val results = boundedCameraMap(chunk, metadataBudget.javaLanes) { target ->
                val read = readBounded(
                    id = target.queryId,
                    physical = target.physicalId != null,
                    minimal = false,
                )
                EnrichmentReadResult(target, read.record, read.failure)
            }
            val publicBatch = ArrayList<CameraMetadataEvidence>()
            val physicalBatch = ArrayList<CameraMetadataEvidence>()
            for (result in results) {
                result.failure?.let { failure ->
                    failures += if (result.target.physicalId == null) failure else failure.copy(
                        transportId = result.target.parentId,
                        physicalId = result.target.physicalId,
                    )
                }
                val record = result.record ?: continue
                val evidence = if (result.target.physicalId == null) {
                    record.toEvidence(
                        source = CameraRouteSource.JAVA_PUBLIC,
                        transportId = CameraTransportId(result.target.queryId),
                    )
                } else {
                    val parent = CameraTransportId(checkNotNull(result.target.parentId))
                    record.toEvidence(
                        source = CameraRouteSource.JAVA_PHYSICAL,
                        transportId = parent,
                        physicalId = PhysicalCameraId(result.target.physicalId),
                        logicalParentId = parent,
                    )
                }
                if (evidence.source == CameraRouteSource.JAVA_PUBLIC) publicBatch += evidence
                else physicalBatch += evidence
                finalEvidence[evidence.address()] = evidence
            }
            emitBatch(publicBatch, physicalBatch, emit)
        }

        val public = finalEvidence.values.filter { it.source == CameraRouteSource.JAVA_PUBLIC }
            .sortedBy(::evidenceKey)
        val physical = finalEvidence.values.filter { it.source == CameraRouteSource.JAVA_PHYSICAL }
            .sortedBy(::evidenceKey)
        return report(public, physical, failures)
    }

    private suspend fun emitBatch(
        publicEvidence: List<CameraMetadataEvidence>,
        physicalEvidence: List<CameraMetadataEvidence>,
        emit: suspend (JavaAdvertisedEvidenceReport) -> Unit,
    ) {
        if (publicEvidence.isEmpty() && physicalEvidence.isEmpty()) return
        emit(report(publicEvidence, physicalEvidence, emptyList()))
    }

    private suspend fun readBounded(
        id: String,
        physical: Boolean,
        minimal: Boolean,
    ): ReadResult {
        val record = try {
            metadataBudget.withJavaMetadata {
                if (minimal) source.readMinimal(id) else source.read(id)
            }
        } catch (_: Exception) {
            null
        }
        if (record == null) {
            return ReadResult(
                null,
                JavaAdvertisedEvidenceFailure(
                    if (physical) JavaAdvertisedEvidenceFailureKind.PHYSICAL_CHARACTERISTICS_UNAVAILABLE
                    else JavaAdvertisedEvidenceFailureKind.CHARACTERISTICS_UNAVAILABLE,
                    transportId = if (physical) null else id,
                    physicalId = if (physical) id else null,
                ),
            )
        }
        if (record.queriedId != id ||
            record.focalLengthsMillimetres.size > AUX_MAX_FOCAL_LENGTHS ||
            record.apertureValues.size > AUX_MAX_APERTURES ||
            record.capabilities.previewStreams.size > AUX_MAX_PREVIEW_STREAMS ||
            record.capabilities.fpsRanges.size > AUX_MAX_FPS_RANGES ||
            record.capabilities.rawSizes.size > AUX_MAX_RAW_SIZES
        ) {
            return ReadResult(
                null,
                JavaAdvertisedEvidenceFailure(
                    JavaAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED,
                    transportId = if (physical) null else id,
                    physicalId = if (physical) id else null,
                ),
            )
        }
        return ReadResult(record, null)
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

    private data class ReadResult(
        val record: JavaAdvertisedCameraRecord?,
        val failure: JavaAdvertisedEvidenceFailure?,
    )

    private data class MinimalReadResult(
        val id: String,
        val record: JavaAdvertisedCameraRecord?,
        val failure: JavaAdvertisedEvidenceFailure?,
    )

    private data class EnrichmentReadResult(
        val target: EnrichmentTarget,
        val record: JavaAdvertisedCameraRecord?,
        val failure: JavaAdvertisedEvidenceFailure?,
    )

    private data class EnrichmentTarget(
        val queryId: String,
        val parentId: String?,
        val physicalId: String?,
    ) {
        companion object {
            fun public(id: String) = EnrichmentTarget(id, null, null)
            fun physical(parent: String, child: String) = EnrichmentTarget(child, parent, child)
        }
    }

    private data class EvidenceAddress(
        val source: CameraRouteSource,
        val transportId: String,
        val physicalId: String?,
    )

    private fun CameraMetadataEvidence.address() = EvidenceAddress(source, transportId.value, physicalId?.value)

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

    override suspend fun readMinimal(id: String): JavaAdvertisedCameraRecord =
        readRecord(id, includeEnrichment = false)

    override suspend fun read(id: String): JavaAdvertisedCameraRecord =
        readRecord(id, includeEnrichment = true)

    private fun readRecord(id: String, includeEnrichment: Boolean): JavaAdvertisedCameraRecord {
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val advertised = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawAdvertised = includeEnrichment &&
            advertised.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
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

        val fpsRanges = if (includeEnrichment) {
            val ranges: Array<Range<Int>> =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            ranges.asSequence()
                .take(AUX_MAX_FPS_RANGES + 1)
                .filter { it.lower > 0 && it.upper >= it.lower }
                .map { CameraFpsCapability(it.lower, it.upper) }
                .distinct()
                .sortedWith(compareBy({ it.minimum }, { it.maximum }))
                .toList()
        } else emptyList()

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
        val apertureArray = if (includeEnrichment) {
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
        } else floatArrayOf()
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
            colorFilterArrangement = if (includeEnrichment) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            } else null,
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
