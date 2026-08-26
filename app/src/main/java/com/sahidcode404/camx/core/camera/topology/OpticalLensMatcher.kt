package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class OpticalLensMatch {
    STRONG_MATCH,
    PROBABLE_MATCH,
    INSUFFICIENT_EVIDENCE,
    CONFLICT,
}

enum class OpticalEvidenceFamily {
    OPTICAL,
    SENSOR,
    GEOMETRY,
    TOPOLOGY,
}

data class OpticalLensComparison(
    val match: OpticalLensMatch,
    val score: Int,
    val evidenceCount: Int,
    val evidenceFamilies: Set<OpticalEvidenceFamily>,
    val positiveReasons: List<String>,
    val negativeReasons: List<String>,
)

internal data class OpticalLensSignature(
    val facing: LensFacing,
    val focalLengthMillimetres: Double?,
    val sensorWidthMillimetres: Double?,
    val sensorHeightMillimetres: Double?,
    val activeArraySize: IntSize?,
    val pixelArraySize: IntSize?,
    val rawSizes: List<IntSize>,
    val colorFilterArrangement: Int?,
    val sensorOrientationDegrees: Int?,
    val aperture: Double?,
    val diagonalFieldOfViewDegrees: Double?,
    val logicalParentId: String?,
    val physicalMemberId: String?,
)

/**
 * CameX-parity optical matcher adapted to CamX's typed evidence model.
 *
 * Route IDs, numeric IDs and discovery sources are never ordinary optical evidence. Pixel, active
 * and RAW dimensions deliberately count as one GEOMETRY family. Normal cross-route grouping
 * requires a strong optical anchor plus an independent corroborating family. Explicit Camera2
 * logical/physical membership is authoritative: same parent/member can prove identity, while
 * different members of one logical parent are always separate.
 */
internal object OpticalLensMatcher {
    private const val FOCAL_STRONG_RELATIVE_TOLERANCE = 0.0125
    private const val FOCAL_PROBABLE_RELATIVE_TOLERANCE = 0.02
    private const val FOCAL_CONFLICT_RELATIVE_DELTA = 0.035

    private const val PHYSICAL_STRONG_RELATIVE_TOLERANCE = 0.015
    private const val PHYSICAL_PROBABLE_RELATIVE_TOLERANCE = 0.03
    private const val PHYSICAL_CONFLICT_RELATIVE_DELTA = 0.06

    private const val FOV_STRONG_DEGREES = 2.0
    private const val FOV_PROBABLE_DEGREES = 4.0
    private const val FOV_CONFLICT_DEGREES = 8.0

    private const val APERTURE_STRONG_RELATIVE_TOLERANCE = 0.05
    private const val APERTURE_CONFLICT_RELATIVE_DELTA = 0.20

    fun compare(
        leftEvidence: List<CameraMetadataEvidence>,
        rightEvidence: List<CameraMetadataEvidence>,
    ): OpticalLensComparison {
        val left = signature(leftEvidence)
        val right = signature(rightEvidence)
        return applyAuthoritativeTopology(compare(left, right), left, right)
    }

    fun signature(evidence: List<CameraMetadataEvidence>): OpticalLensSignature {
        val knownFacings = evidence.map { it.facing }
            .filterNot { it == LensFacing.UNKNOWN }
            .distinct()
        val focalLists = evidence.map { item ->
            item.focalLengthsMillimetres
                .asSequence()
                .filter { it.isFinite() && it > 0f }
                .map(Float::toDouble)
                .distinct()
                .sorted()
                .toList()
        }.filter(List<Double>::isNotEmpty)
        val focal = if (focalLists.any { it.size != 1 }) null else {
            focalLists.map { it.single() }.medianPositive()
        }
        val width = evidence.mapNotNull { it.sensorPhysicalWidthMillimetres?.toDouble() }.medianPositive()
        val height = evidence.mapNotNull { it.sensorPhysicalHeightMillimetres?.toDouble() }.medianPositive()
        val active = votedSize(evidence.mapNotNull { it.activeArray })
        val pixel = votedSize(evidence.mapNotNull { it.pixelArray })
        val raw = evidence.asSequence()
            .flatMap { it.capabilities.rawSizes.asSequence() }
            .distinct()
            .sortedWith(compareBy({ it.width }, { it.height }))
            .toList()
        val knownCfas = evidence.mapNotNull { it.colorFilterArrangement }.distinct()
        val knownOrientations = evidence.mapNotNull { it.sensorOrientationDegrees }.distinct()
        val apertures = evidence.asSequence()
            .flatMap { it.apertureValues.asSequence() }
            .filter { it.isFinite() && it > 0f }
            .map(Float::toDouble)
            .toList()
        val topologyPairs = evidence.mapNotNull { item ->
            val member = item.physicalId?.value ?: return@mapNotNull null
            val parent = item.logicalParentId?.value ?: item.transportId.value
            parent to member
        }.distinct()
        val topology = topologyPairs.singleOrNull()
        val fov = if (focal != null && width != null && height != null) {
            Math.toDegrees(2.0 * atan(hypot(width, height) / (2.0 * focal)))
                .takeIf { it.isFinite() && it > 0.0 && it < 180.0 }
        } else {
            null
        }
        return OpticalLensSignature(
            facing = knownFacings.singleOrNull() ?: LensFacing.UNKNOWN,
            focalLengthMillimetres = focal,
            sensorWidthMillimetres = width,
            sensorHeightMillimetres = height,
            activeArraySize = active,
            pixelArraySize = pixel,
            rawSizes = raw,
            colorFilterArrangement = knownCfas.singleOrNull(),
            sensorOrientationDegrees = knownOrientations.singleOrNull(),
            aperture = apertures.minOrNull(),
            diagonalFieldOfViewDegrees = fov,
            logicalParentId = topology?.first,
            physicalMemberId = topology?.second,
        )
    }

    fun compare(left: OpticalLensSignature, right: OpticalLensSignature): OpticalLensComparison {
        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()
        val familyScores = linkedMapOf<OpticalEvidenceFamily, Int>()
        var strongOpticalAnchor = false
        var probableOpticalAnchor = false
        var focalDisagreementBlocksAutomaticMerge = false

        if (left.facing != LensFacing.UNKNOWN && right.facing != LensFacing.UNKNOWN) {
            if (left.facing != right.facing) return conflict("different facing")
            positive += "facing agrees (context only)"
        }

        val focalRelation = compareRelative(
            left.focalLengthMillimetres,
            right.focalLengthMillimetres,
            FOCAL_STRONG_RELATIVE_TOLERANCE,
            FOCAL_PROBABLE_RELATIVE_TOLERANCE,
            FOCAL_CONFLICT_RELATIVE_DELTA,
        )
        when {
            focalRelation?.conflict == true -> return conflict("meaningfully different focal length")
            focalRelation?.strong == true -> {
                familyScores.raise(OpticalEvidenceFamily.OPTICAL, 50)
                strongOpticalAnchor = true
                positive += "focal length strongly agrees"
            }
            focalRelation != null -> {
                familyScores.raise(OpticalEvidenceFamily.OPTICAL, 30)
                probableOpticalAnchor = true
                positive += "focal length probably agrees"
            }
            validPair(left.focalLengthMillimetres, right.focalLengthMillimetres) -> {
                focalDisagreementBlocksAutomaticMerge = true
                negative += "focal lengths differ outside alias tolerance"
            }
        }

        if (validPair(left.sensorWidthMillimetres, right.sensorWidthMillimetres) &&
            validPair(left.sensorHeightMillimetres, right.sensorHeightMillimetres)
        ) {
            val widthDelta = relativeDelta(
                checkNotNull(left.sensorWidthMillimetres),
                checkNotNull(right.sensorWidthMillimetres),
            )
            val heightDelta = relativeDelta(
                checkNotNull(left.sensorHeightMillimetres),
                checkNotNull(right.sensorHeightMillimetres),
            )
            val delta = max(widthDelta, heightDelta)
            when {
                delta > PHYSICAL_CONFLICT_RELATIVE_DELTA ->
                    return conflict("meaningfully different physical sensor size")
                delta <= PHYSICAL_STRONG_RELATIVE_TOLERANCE -> {
                    familyScores.raise(OpticalEvidenceFamily.SENSOR, 25)
                    positive += "physical sensor size strongly agrees"
                }
                delta <= PHYSICAL_PROBABLE_RELATIVE_TOLERANCE -> {
                    familyScores.raise(OpticalEvidenceFamily.SENSOR, 14)
                    positive += "physical sensor size probably agrees"
                }
                else -> negative += "physical sensor size differs outside corroboration tolerance"
            }
        }

        if (left.colorFilterArrangement != null && right.colorFilterArrangement != null) {
            if (left.colorFilterArrangement != right.colorFilterArrangement) {
                return conflict("different authoritative CFA")
            }
            familyScores.raise(OpticalEvidenceFamily.SENSOR, 20)
            positive += "CFA agrees"
        }

        var geometryScore = 0
        val geometryPositive = mutableListOf<String>()
        val geometryNegative = mutableListOf<String>()
        geometryEvidence(left.pixelArraySize, right.pixelArraySize)?.let { relation ->
            if (relation.conflict) geometryNegative += "pixel arrays differ" else {
                geometryScore = max(geometryScore, if (relation.strong) 18 else 8)
                geometryPositive += if (relation.strong) "pixel array agrees" else "pixel array is binning-compatible"
            }
        }
        geometryEvidence(left.activeArraySize, right.activeArraySize)?.let { relation ->
            if (relation.conflict) geometryNegative += "active arrays differ" else {
                geometryScore = max(geometryScore, if (relation.strong) 18 else 8)
                geometryPositive += if (relation.strong) "active array agrees" else "active array is crop/binning-compatible"
            }
        }
        if (left.rawSizes.isNotEmpty() && right.rawSizes.isNotEmpty()) {
            val exact = left.rawSizes.any { it in right.rawSizes }
            val compatible = exact || left.rawSizes.any { a -> right.rawSizes.any { b -> binningCompatible(a, b) } }
            when {
                exact -> {
                    geometryScore = max(geometryScore, 18)
                    geometryPositive += "RAW dimensions agree"
                }
                compatible -> {
                    geometryScore = max(geometryScore, 8)
                    geometryPositive += "RAW dimensions are binning-compatible"
                }
                else -> geometryNegative += "RAW dimensions differ"
            }
        }
        if (geometryScore > 0) {
            familyScores.raise(OpticalEvidenceFamily.GEOMETRY, geometryScore)
            positive += geometryPositive
        }
        negative += geometryNegative

        if (left.sensorOrientationDegrees != null && right.sensorOrientationDegrees != null) {
            if (left.sensorOrientationDegrees == right.sensorOrientationDegrees) {
                positive += "sensor orientation agrees (context only)"
            } else {
                negative += "sensor orientation differs (context only)"
            }
        }

        compareRelative(
            left.aperture,
            right.aperture,
            APERTURE_STRONG_RELATIVE_TOLERANCE,
            APERTURE_STRONG_RELATIVE_TOLERANCE * 2,
            APERTURE_CONFLICT_RELATIVE_DELTA,
        )?.let { relation ->
            if (relation.conflict) return conflict("meaningfully different aperture")
            familyScores.raise(OpticalEvidenceFamily.OPTICAL, if (relation.strong) 12 else 6)
            positive += if (relation.strong) "aperture strongly agrees" else "aperture probably agrees"
        }

        if (left.diagonalFieldOfViewDegrees != null && right.diagonalFieldOfViewDegrees != null) {
            val delta = abs(left.diagonalFieldOfViewDegrees - right.diagonalFieldOfViewDegrees)
            when {
                delta > FOV_CONFLICT_DEGREES -> return conflict("clearly different field of view")
                delta <= FOV_STRONG_DEGREES -> {
                    familyScores.raise(OpticalEvidenceFamily.OPTICAL, 42)
                    strongOpticalAnchor = true
                    positive += "field of view strongly agrees"
                }
                delta <= FOV_PROBABLE_DEGREES -> {
                    familyScores.raise(OpticalEvidenceFamily.OPTICAL, 24)
                    probableOpticalAnchor = true
                    positive += "field of view probably agrees"
                }
                else -> negative += "field of view differs outside alias tolerance"
            }
        }

        val corroboratingFamilies = familyScores.keys - OpticalEvidenceFamily.OPTICAL
        val score = familyScores.values.sum().coerceAtMost(100)
        val match = when {
            !focalDisagreementBlocksAutomaticMerge && strongOpticalAnchor && corroboratingFamilies.isNotEmpty() ->
                OpticalLensMatch.STRONG_MATCH
            (strongOpticalAnchor || probableOpticalAnchor) && corroboratingFamilies.isNotEmpty() ->
                OpticalLensMatch.PROBABLE_MATCH
            strongOpticalAnchor || probableOpticalAnchor -> OpticalLensMatch.PROBABLE_MATCH
            else -> OpticalLensMatch.INSUFFICIENT_EVIDENCE
        }
        return OpticalLensComparison(
            match = match,
            score = score,
            evidenceCount = familyScores.size,
            evidenceFamilies = familyScores.keys.toSet(),
            positiveReasons = positive,
            negativeReasons = negative,
        )
    }

    private fun applyAuthoritativeTopology(
        base: OpticalLensComparison,
        left: OpticalLensSignature,
        right: OpticalLensSignature,
    ): OpticalLensComparison {
        val leftParent = left.logicalParentId ?: return base
        val rightParent = right.logicalParentId ?: return base
        val leftMember = left.physicalMemberId ?: return base
        val rightMember = right.physicalMemberId ?: return base
        if (leftParent != rightParent) return base
        if (leftMember != rightMember) {
            return conflict("different authoritative physical members of the same logical camera")
        }
        if (base.match == OpticalLensMatch.CONFLICT) return base
        val positives = base.positiveReasons + "authoritative topology names the same physical member"
        val families = base.evidenceFamilies + OpticalEvidenceFamily.TOPOLOGY
        return base.copy(
            match = OpticalLensMatch.STRONG_MATCH,
            score = (base.score + 50).coerceAtMost(100),
            evidenceCount = families.size,
            evidenceFamilies = families,
            positiveReasons = positives,
        )
    }

    private fun conflict(reason: String) = OpticalLensComparison(
        match = OpticalLensMatch.CONFLICT,
        score = Int.MIN_VALUE,
        evidenceCount = 0,
        evidenceFamilies = emptySet(),
        positiveReasons = emptyList(),
        negativeReasons = listOf(reason),
    )

    private fun compareRelative(
        left: Double?,
        right: Double?,
        strongTolerance: Double,
        probableTolerance: Double,
        conflictDelta: Double,
    ): RelativeRelation? {
        if (!validPair(left, right)) return null
        val delta = relativeDelta(checkNotNull(left), checkNotNull(right))
        return when {
            delta > conflictDelta -> RelativeRelation(strong = false, conflict = true)
            delta <= strongTolerance -> RelativeRelation(strong = true, conflict = false)
            delta <= probableTolerance -> RelativeRelation(strong = false, conflict = false)
            else -> null
        }
    }

    private fun geometryEvidence(left: IntSize?, right: IntSize?): RelativeRelation? {
        if (left == null || right == null) return null
        return when {
            left == right -> RelativeRelation(strong = true, conflict = false)
            binningCompatible(left, right) -> RelativeRelation(strong = false, conflict = false)
            else -> RelativeRelation(strong = false, conflict = true)
        }
    }

    private fun binningCompatible(left: IntSize, right: IntSize): Boolean {
        val widthRatio = max(left.width, right.width).toDouble() / min(left.width, right.width).toDouble()
        val heightRatio = max(left.height, right.height).toDouble() / min(left.height, right.height).toDouble()
        if (abs(widthRatio - heightRatio) > 0.03) return false
        return listOf(2.0, 3.0, 4.0).any { factor -> abs(widthRatio - factor) <= 0.04 }
    }

    private fun relativeDelta(left: Double, right: Double): Double =
        abs(left - right) / max(abs(left), abs(right)).coerceAtLeast(1e-9)

    private fun validPair(left: Double?, right: Double?): Boolean =
        left != null && right != null && left.isFinite() && right.isFinite() && left > 0.0 && right > 0.0

    private fun MutableMap<OpticalEvidenceFamily, Int>.raise(family: OpticalEvidenceFamily, score: Int) {
        this[family] = max(this[family] ?: 0, score)
    }

    private fun List<Double>.medianPositive(): Double? {
        val values = filter { it.isFinite() && it > 0.0 }.sorted()
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

    private fun votedSize(values: List<IntSize>): IntSize? = values
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<IntSize, Int>> { it.value }
                .thenByDescending { it.key.area }
                .thenBy { it.key.width }
                .thenBy { it.key.height },
        )
        .firstOrNull()
        ?.key

    private data class RelativeRelation(val strong: Boolean, val conflict: Boolean)
}
