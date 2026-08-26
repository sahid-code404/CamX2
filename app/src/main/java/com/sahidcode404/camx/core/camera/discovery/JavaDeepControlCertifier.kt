package com.sahidcode404.camx.core.camera.discovery

import android.hardware.camera2.CameraAccessException
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
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Typed result of one Java metadata lookup. No camera resource is owned by this source. */
internal sealed interface JavaDeepMetadataRead {
    data class Success(val record: JavaAdvertisedCameraRecord) : JavaDeepMetadataRead
    data object NotFound : JavaDeepMetadataRead
    data object AccessDenied : JavaDeepMetadataRead
    data object MetadataError : JavaDeepMetadataRead
    data object Cancelled : JavaDeepMetadataRead
}

internal fun interface JavaDeepCameraMetadataSource {
    suspend fun read(candidateId: String): JavaDeepMetadataRead
}

enum class JavaDeepCertificationKind {
    CERTIFIED,
    JAVA_NOT_FOUND,
    JAVA_ACCESS_DENIED,
    JAVA_METADATA_ERROR,
    NO_PRIVATE_PREVIEW,
    NO_FPS_EVIDENCE,
    MISSING_ORIENTATION,
    NON_PHOTOGRAPHIC,
    BOUND_EXCEEDED,
    CANCELLED,
    ALREADY_REPRESENTED,
}

data class JavaDeepCertificationOutcome(
    val candidate: DeepAuxCandidate,
    val kind: JavaDeepCertificationKind,
    val discoveredPhysicalIds: List<String> = emptyList(),
)

data class JavaDeepCertificationReport(
    val snapshot: CameraEvidenceSnapshot,
    val outcomes: List<JavaDeepCertificationOutcome>,
)

/**
 * Metadata-only Java control certification for candidates that already have credible NDK_DEEP
 * metadata. Certification proves only that CameraManager metadata is queryable; it never proves a
 * device/session/preview and therefore publishes metadata with JAVA_DEEP_PROBED provenance only.
 */
internal class JavaDeepControlCertifier(
    private val environment: CameraEnvironmentFingerprint,
    private val metadataBudget: DiscoveryMetadataBudget,
    private val metadataSource: JavaDeepCameraMetadataSource,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    constructor(
        cameraManager: CameraManager,
        environment: CameraEnvironmentFingerprint,
        metadataBudget: DiscoveryMetadataBudget,
        clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    ) : this(
        environment = environment,
        metadataBudget = metadataBudget,
        metadataSource = AndroidJavaDeepCameraMetadataSource(cameraManager),
        clockNanos = clockNanos,
    )

    suspend fun certifyIncrementally(
        ndkOutcomes: List<DeepAuxCandidateOutcome>,
        existingJavaEvidence: Collection<CameraMetadataEvidence>,
        emit: suspend (JavaDeepCertificationReport) -> Unit,
    ): JavaDeepCertificationReport {
        coroutineContext.ensureActive()
        val ordered = LinkedHashMap<String, DeepAuxCandidate>()
        ndkOutcomes.asSequence()
            .filter { it.outcome == DeepAuxOutcomeKind.VALID_METADATA }
            .forEach { outcome ->
                if (!ordered.containsKey(outcome.candidate.transportId)) {
                    ordered[outcome.candidate.transportId] = outcome.candidate
                }
            }

        val allEvidence = ArrayList<CameraMetadataEvidence>()
        val allOutcomes = ArrayList<JavaDeepCertificationOutcome>()
        val eligible = ArrayList<DeepAuxCandidate>()
        ordered.values.forEachIndexed { index, candidate ->
            when {
                index >= DEEP_AUX_HARD_MAXIMUM_CANDIDATES ||
                    !DeepAuxCandidatePlanner.isSafeExactId(candidate.transportId) -> {
                    allOutcomes += JavaDeepCertificationOutcome(
                        candidate,
                        JavaDeepCertificationKind.BOUND_EXCEEDED,
                    )
                }
                isAlreadyRepresented(candidate.transportId, existingJavaEvidence) -> {
                    allOutcomes += JavaDeepCertificationOutcome(
                        candidate,
                        JavaDeepCertificationKind.ALREADY_REPRESENTED,
                    )
                }
                else -> eligible += candidate
            }
        }

        for (chunk in eligible.chunked(metadataBudget.javaLanes)) {
            coroutineContext.ensureActive()
            val results = boundedCameraMap(chunk, metadataBudget.javaLanes, ::certifyOne)
            val evidence = results.mapNotNull(CertificationResult::evidence)
            val outcomes = results.map(CertificationResult::outcome)
            allEvidence += evidence
            allOutcomes += outcomes
            emit(report(evidence, outcomes))
        }

        if (eligible.isEmpty() && allOutcomes.isNotEmpty()) {
            emit(report(emptyList(), allOutcomes))
        }
        return report(allEvidence, allOutcomes)
    }

    suspend fun certify(
        ndkOutcomes: List<DeepAuxCandidateOutcome>,
        existingJavaEvidence: Collection<CameraMetadataEvidence> = emptyList(),
    ): JavaDeepCertificationReport = certifyIncrementally(ndkOutcomes, existingJavaEvidence) {}

    private suspend fun certifyOne(candidate: DeepAuxCandidate): CertificationResult {
        val read = try {
            metadataBudget.withJavaMetadata { metadataSource.read(candidate.transportId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            JavaDeepMetadataRead.AccessDenied
        } catch (_: IllegalArgumentException) {
            JavaDeepMetadataRead.NotFound
        } catch (_: Throwable) {
            JavaDeepMetadataRead.MetadataError
        }
        return when (read) {
            is JavaDeepMetadataRead.Success -> certifyRecord(candidate, read.record)
            JavaDeepMetadataRead.NotFound -> outcome(candidate, JavaDeepCertificationKind.JAVA_NOT_FOUND)
            JavaDeepMetadataRead.AccessDenied -> outcome(candidate, JavaDeepCertificationKind.JAVA_ACCESS_DENIED)
            JavaDeepMetadataRead.MetadataError -> outcome(candidate, JavaDeepCertificationKind.JAVA_METADATA_ERROR)
            JavaDeepMetadataRead.Cancelled -> outcome(candidate, JavaDeepCertificationKind.CANCELLED)
        }
    }

    private fun certifyRecord(
        candidate: DeepAuxCandidate,
        record: JavaAdvertisedCameraRecord,
    ): CertificationResult {
        if (record.queriedId != candidate.transportId ||
            record.focalLengthsMillimetres.size > AUX_MAX_FOCAL_LENGTHS ||
            record.capabilities.previewStreams.size > AUX_MAX_PREVIEW_STREAMS ||
            record.capabilities.fpsRanges.size > AUX_MAX_FPS_RANGES ||
            record.physicalIds.size > AUX_MAX_PHYSICAL_IDS_PER_LOGICAL
        ) {
            return outcome(candidate, JavaDeepCertificationKind.BOUND_EXCEEDED)
        }
        val privatePreview = record.capabilities.previewStreams
            .filter { it.type == PreviewStreamType.CAMERA2_PRIVATE }
            .distinct()
            .sortedWith(compareBy({ it.size.area }, { it.size.width }, { it.size.height }))
        if (privatePreview.isEmpty()) return outcome(candidate, JavaDeepCertificationKind.NO_PRIVATE_PREVIEW)
        val fps = record.capabilities.fpsRanges
            .filter { it.minimum > 0 && it.maximum >= it.minimum }
            .distinct()
            .sortedWith(compareBy({ it.minimum }, { it.maximum }))
        if (fps.isEmpty()) return outcome(candidate, JavaDeepCertificationKind.NO_FPS_EVIDENCE)
        if (record.sensorOrientationDegrees == null) {
            return outcome(candidate, JavaDeepCertificationKind.MISSING_ORIENTATION)
        }
        if (record.focalLengthsMillimetres.isEmpty()) {
            return outcome(candidate, JavaDeepCertificationKind.NON_PHOTOGRAPHIC)
        }

        val physicalIds = record.physicalIds.asSequence()
            .filter(DeepAuxCandidatePlanner::isSafeExactId)
            .distinct()
            .sorted()
            .toList()
        val evidence = CameraMetadataEvidence(
            source = CameraRouteSource.JAVA_DEEP_PROBED,
            transportId = CameraTransportId(candidate.transportId),
            facing = record.facing,
            focalLengthsMillimetres = immutableList(record.focalLengthsMillimetres),
            sensorPhysicalWidthMillimetres = record.sensorPhysicalWidthMillimetres,
            sensorPhysicalHeightMillimetres = record.sensorPhysicalHeightMillimetres,
            activeArray = record.activeArray,
            pixelArray = record.pixelArray,
            sensorOrientationDegrees = record.sensorOrientationDegrees,
            apertureValues = emptyList(),
            colorFilterArrangement = null,
            capabilities = CameraCapabilities(
                previewStreams = immutableList(privatePreview),
                fpsRanges = immutableList(fps),
                rawSizes = emptyList(),
            ),
        )
        return CertificationResult(
            evidence = evidence,
            outcome = JavaDeepCertificationOutcome(
                candidate = candidate,
                kind = JavaDeepCertificationKind.CERTIFIED,
                discoveredPhysicalIds = immutableList(physicalIds),
            ),
        )
    }

    private fun isAlreadyRepresented(
        candidateId: String,
        evidence: Collection<CameraMetadataEvidence>,
    ): Boolean = evidence.any { item ->
        when (item.source) {
            CameraRouteSource.JAVA_PUBLIC -> item.physicalId == null && item.transportId.value == candidateId
            CameraRouteSource.JAVA_PHYSICAL -> item.physicalId?.value == candidateId
            CameraRouteSource.JAVA_DEEP_PROBED -> item.physicalId == null && item.transportId.value == candidateId
            CameraRouteSource.NDK_ADVERTISED,
            CameraRouteSource.NDK_DEEP,
            -> false
        }
    }

    private fun outcome(
        candidate: DeepAuxCandidate,
        kind: JavaDeepCertificationKind,
    ) = CertificationResult(
        evidence = null,
        outcome = JavaDeepCertificationOutcome(candidate, kind),
    )

    private fun report(
        evidence: Collection<CameraMetadataEvidence>,
        outcomes: Collection<JavaDeepCertificationOutcome>,
    ) = JavaDeepCertificationReport(
        snapshot = CameraEvidenceSnapshot(
            source = CameraRouteSource.JAVA_DEEP_PROBED,
            environment = environment,
            evidence = immutableList(evidence),
            completedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
        ),
        outcomes = immutableList(outcomes),
    )

    private data class CertificationResult(
        val evidence: CameraMetadataEvidence?,
        val outcome: JavaDeepCertificationOutcome,
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}

/**
 * The Android implementation reads only control/preview metadata required by deep certification.
 * In particular it does not enumerate RAW, high-speed, manual-request, aperture, or CFA tables.
 */
internal class AndroidJavaDeepCameraMetadataSource(
    private val cameraManager: CameraManager,
) : JavaDeepCameraMetadataSource {
    override suspend fun read(candidateId: String): JavaDeepMetadataRead {
        return try {
            JavaDeepMetadataRead.Success(readRecord(candidateId))
        } catch (_: IllegalArgumentException) {
            JavaDeepMetadataRead.NotFound
        } catch (_: SecurityException) {
            JavaDeepMetadataRead.AccessDenied
        } catch (error: CameraAccessException) {
            if (error.reason == CameraAccessException.CAMERA_DISABLED) {
                JavaDeepMetadataRead.AccessDenied
            } else {
                JavaDeepMetadataRead.MetadataError
            }
        } catch (_: Exception) {
            JavaDeepMetadataRead.MetadataError
        }
    }

    private fun readRecord(id: String): JavaAdvertisedCameraRecord {
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes: Array<Size> = map?.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
        val previewStreams = previewSizes.asSequence()
            .take(AUX_MAX_PREVIEW_STREAMS + 1)
            .filter { it.width > 0 && it.height > 0 }
            .map { size ->
                val duration = runCatching {
                    map?.getOutputMinFrameDuration(SurfaceHolder::class.java, size) ?: 0L
                }.getOrDefault(0L)
                CameraStreamCapability(
                    type = PreviewStreamType.CAMERA2_PRIVATE,
                    size = IntSize(size.width, size.height),
                    minimumFrameDurationNs = duration.takeIf { it > 0L },
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

        val focalArray = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val focalLengths = focalArray.asSequence()
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
            .take(AUX_MAX_FOCAL_LENGTHS + 1)
            .toList()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
        val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?.takeIf { it in 0..270 && it % 90 == 0 }
        val advertised = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val logicalAdvertised = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            advertised.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val physicalIds = if (logicalAdvertised) {
            characteristics.physicalCameraIds.asSequence()
                .take(AUX_MAX_PHYSICAL_IDS_PER_LOGICAL + 1)
                .toList()
        } else {
            emptyList()
        }

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
            apertureValues = emptyList(),
            colorFilterArrangement = null,
            capabilities = CameraCapabilities(
                previewStreams = immutableList(previewStreams),
                fpsRanges = immutableList(fpsRanges),
                rawSizes = emptyList(),
            ),
            physicalIds = immutableList(physicalIds),
        )
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
