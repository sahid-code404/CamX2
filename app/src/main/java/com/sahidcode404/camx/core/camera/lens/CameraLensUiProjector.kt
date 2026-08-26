package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.math.BigDecimal
import java.math.RoundingMode

/** Session-local hardware-test state. It deliberately does not mutate persisted CAMX-107 trust. */
enum class LensTestStatus {
    ADVERTISED,
    OPENING,
    VERIFIED,
    FAILED,
}

/** Safe immutable presentation model. It contains no raw camera transport identifiers. */
data class CameraLensUiItem(
    val canonicalFingerprint: CanonicalLensFingerprint,
    val facing: LensFacing,
    val primaryLabel: String,
    val secondaryOpticalLabel: String?,
    val enabled: Boolean,
    val selected: Boolean,
    val status: LensTestStatus,
)

internal data class LensPreviewMetadata(
    val sensorOrientationDegrees: Int,
    val lensFacing: LensFacing,
)

/** Internal orchestration target. Feature UI never receives this object. */
internal data class LensSelectionTarget(
    val canonicalFingerprint: CanonicalLensFingerprint,
    val profileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val route: CameraRoute,
    val previewMetadata: LensPreviewMetadata,
)

internal data class CameraLensProjection(
    val items: List<CameraLensUiItem>,
    val targets: Map<CanonicalLensFingerprint, LensSelectionTarget>,
)

internal data class CameraLensProjectionInput(
    val topology: CameraTopologySnapshot?,
    val runtimeApiLevel: Int,
    val activeSelection: ActiveCameraSelection?,
    val statusByLens: Map<CanonicalLensFingerprint, LensTestStatus> = emptyMap(),
)

/** Pure, deterministic CAMX-107 topology -> lens-test projection. */
internal object CameraLensUiProjector {
    fun project(input: CameraLensProjectionInput): CameraLensProjection {
        val topology = input.topology ?: return CameraLensProjection(emptyList(), emptyMap())
        val activeProfile = input.activeSelection?.routeId?.let { activeRouteId ->
            topology.canonicalLenses.asSequence()
                .flatMap { lens -> lens.profiles.asSequence() }
                .firstOrNull { profile -> profile.route.id == activeRouteId }
        }

        val works = topology.canonicalLenses.mapNotNull { lens ->
            val target = chooseTarget(topology, lens, input.runtimeApiLevel, activeProfile, input.statusByLens)
                ?: return@mapNotNull null
            val optical = opticalEvidence(topology, target.route)
            LensWork(
                lens = lens,
                target = target,
                optical = optical,
                status = input.statusByLens[lens.fingerprint] ?: LensTestStatus.ADVERTISED,
            )
        }
        val ordered = works.sortedWith(lensOrder())
        val activeWork = ordered.firstOrNull { work ->
            work.status == LensTestStatus.VERIFIED &&
                work.lens.facing == LensFacing.BACK &&
                input.activeSelection?.routeId == work.target.routeId &&
                work.optical.metric != null
        }
        val referenceMetric = activeWork?.optical?.metric

        val targets = LinkedHashMap<CanonicalLensFingerprint, LensSelectionTarget>(ordered.size)
        val items = ordered.map { work ->
            targets[work.lens.fingerprint] = work.target
            val labels = labelsFor(work, referenceMetric)
            CameraLensUiItem(
                canonicalFingerprint = work.lens.fingerprint,
                facing = work.lens.facing,
                primaryLabel = labels.first,
                secondaryOpticalLabel = labels.second,
                enabled = true,
                selected = work.status == LensTestStatus.VERIFIED &&
                    input.activeSelection?.routeId == work.target.routeId,
                status = work.status,
            )
        }
        return CameraLensProjection(items = items, targets = targets)
    }

    private fun chooseTarget(
        topology: CameraTopologySnapshot,
        lens: CanonicalLens,
        runtimeApiLevel: Int,
        activeProfile: CameraProfile?,
        statusByLens: Map<CanonicalLensFingerprint, LensTestStatus>,
    ): LensSelectionTarget? {
        val candidates = lens.profiles.mapNotNull { profile ->
            previewTarget(topology, lens, profile, runtimeApiLevel)
        }
        if (candidates.isEmpty()) return null
        val activeVerified = activeProfile
            ?.takeIf { it.canonicalFingerprint == lens.fingerprint }
            ?.takeIf { statusByLens[lens.fingerprint] == LensTestStatus.VERIFIED }
            ?.let { active -> candidates.firstOrNull { it.profileFingerprint == active.fingerprint } }
        if (activeVerified != null) return activeVerified
        return candidates.minWith(
            compareBy<LensSelectionTarget>(
                { target -> if (target.route.physicalCameraId == null) 0 else 1 },
                { target -> target.profileFingerprint.value },
            ),
        )
    }

    private fun previewTarget(
        topology: CameraTopologySnapshot,
        lens: CanonicalLens,
        profile: CameraProfile,
        runtimeApiLevel: Int,
    ): LensSelectionTarget? {
        val route = profile.route
        val controllable = if (route.physicalCameraId == null) {
            CameraRouteSource.JAVA_PUBLIC in route.sources
        } else {
            runtimeApiLevel >= 28 && CameraRouteSource.JAVA_PHYSICAL in route.sources
        }
        if (!controllable || route.capabilities.previewStreams.isEmpty()) return null
        val evidence = topology.evidence.filter { it.matches(route) }
        val orientations = evidence.mapNotNull { it.sensorOrientationDegrees }.distinct()
        val orientation = orientations.singleOrNull() ?: return null
        val evidenceFacings = evidence.map { it.facing }
            .filterNot { it == LensFacing.UNKNOWN }
            .distinct()
        val facing = when {
            lens.facing != LensFacing.UNKNOWN -> lens.facing
            evidenceFacings.size == 1 -> evidenceFacings.single()
            else -> LensFacing.UNKNOWN
        }
        return LensSelectionTarget(
            canonicalFingerprint = lens.fingerprint,
            profileFingerprint = profile.fingerprint,
            routeId = route.id,
            route = route,
            previewMetadata = LensPreviewMetadata(
                sensorOrientationDegrees = orientation,
                lensFacing = facing,
            ),
        )
    }

    private fun CameraMetadataEvidence.matches(route: CameraRoute): Boolean =
        transportId == route.openCameraId && physicalId == route.physicalCameraId

    private data class OpticalEvidence(
        val focalMillimetres: Float?,
        val metric: Double?,
    )

    private data class LensWork(
        val lens: CanonicalLens,
        val target: LensSelectionTarget,
        val optical: OpticalEvidence,
        val status: LensTestStatus,
    )

    private fun opticalEvidence(topology: CameraTopologySnapshot, route: CameraRoute): OpticalEvidence {
        val evidence = topology.evidence.filter { it.matches(route) }
        if (evidence.any { it.focalLengthsMillimetres.size > 1 }) return OpticalEvidence(null, null)
        val focals = evidence.asSequence()
            .filter { it.focalLengthsMillimetres.size == 1 }
            .map { it.focalLengthsMillimetres.single() }
            .distinctBy(Float::toRawBits)
            .toList()
        val focal = focals.singleOrNull()
        val widths = evidence.mapNotNull { it.sensorPhysicalWidthMillimetres }
            .distinctBy(Float::toRawBits)
        val width = widths.singleOrNull()
        val metric = if (focal != null && width != null && width > 0f) {
            focal.toDouble() / width.toDouble()
        } else {
            null
        }
        return OpticalEvidence(focal, metric?.takeIf { it.isFinite() && it > 0.0 })
    }

    private fun lensOrder(): Comparator<LensWork> = Comparator { left, right ->
        val facing = facingPriority(left.lens.facing).compareTo(facingPriority(right.lens.facing))
        if (facing != 0) return@Comparator facing
        if (left.lens.facing == LensFacing.BACK) {
            val leftMetric = left.optical.metric
            val rightMetric = right.optical.metric
            when {
                leftMetric != null && rightMetric != null -> {
                    val metric = leftMetric.compareTo(rightMetric)
                    if (metric != 0) return@Comparator metric
                }
                leftMetric != null -> return@Comparator -1
                rightMetric != null -> return@Comparator 1
            }
        }
        left.lens.fingerprint.value.compareTo(right.lens.fingerprint.value)
    }

    private fun facingPriority(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }

    private fun labelsFor(work: LensWork, referenceMetric: Double?): Pair<String, String?> = when (work.lens.facing) {
        LensFacing.FRONT -> "Front" to work.optical.focalMillimetres?.let(::formatFocal)
        LensFacing.EXTERNAL -> "External" to work.optical.focalMillimetres?.let(::formatFocal)
        LensFacing.BACK -> {
            val focal = work.optical.focalMillimetres?.let(::formatFocal)
            val ratio = if (referenceMetric != null && work.optical.metric != null) {
                work.optical.metric / referenceMetric
            } else {
                null
            }
            if (ratio != null && ratio.isFinite() && ratio > 0.0) {
                formatRatio(ratio) to focal
            } else {
                (focal ?: "Rear") to null
            }
        }
        LensFacing.UNKNOWN -> (work.optical.focalMillimetres?.let(::formatFocal) ?: "Lens") to null
    }

    private fun formatRatio(value: Double): String {
        val decimal = BigDecimal.valueOf(value)
            .setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return "$decimal×"
    }

    private fun formatFocal(value: Float): String {
        val decimal = BigDecimal(value.toString())
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return "$decimal mm"
    }
}
