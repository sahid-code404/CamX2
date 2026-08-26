package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** Transport-independent representative optical metadata for one canonical lens. */
internal data class CanonicalLensOpticalMetadata(
    val facing: LensFacing,
    val focalLengthMillimetres: Float?,
    val sensorPhysicalWidthMillimetres: Float?,
    val sensorPhysicalHeightMillimetres: Float?,
    val activeArray: IntSize?,
    val pixelArray: IntSize?,
    val sensorOrientationDegrees: Int?,
    val aperture: Float?,
    val colorFilterArrangement: Int?,
)

/** CameX-parity canonical optics: deterministic voting plus transport-free stable identity. */
internal object CanonicalLensOptics {
    private const val FOCAL_CONSENSUS_RELATIVE_DELTA = 0.035
    private const val SENSOR_VOTE_STEP_MM = 0.05
    private const val SENSOR_FINGERPRINT_STEP_MM = 0.02
    private const val FOCAL_FINGERPRINT_STEP_MM = 0.05
    private const val APERTURE_STEP = 0.05
    private const val GEOMETRY_STEP_PIXELS = 100

    fun merge(values: Collection<CameraMetadataEvidence>): CanonicalLensOpticalMetadata {
        val evidence = values.toList()
        val focal = chooseFocal(evidence)
        val sensor = chooseSensor(evidence)
        val active = chooseGeometry(evidence.mapNotNull { it.activeArray })
        val pixel = chooseGeometry(evidence.mapNotNull { it.pixelArray })
        val orientation = chooseKnown(evidence.mapNotNull { it.sensorOrientationDegrees })
        val facing = chooseFacing(evidence.map { it.facing })
        val aperture = evidence.flatMap { it.apertureValues }
            .map(Float::toDouble)
            .filter(::positiveFinite)
            .distinct()
            .sorted()
            .median()
            ?.let { quantize(it, APERTURE_STEP).toFloat() }
        val cfa = chooseKnown(evidence.mapNotNull { it.colorFilterArrangement })
        return CanonicalLensOpticalMetadata(
            facing = facing,
            focalLengthMillimetres = focal,
            sensorPhysicalWidthMillimetres = sensor?.first,
            sensorPhysicalHeightMillimetres = sensor?.second,
            activeArray = active,
            pixelArray = pixel,
            sensorOrientationDegrees = orientation,
            aperture = aperture,
            colorFilterArrangement = cfa,
        )
    }

    fun stableFingerprint(metadata: CanonicalLensOpticalMetadata): CanonicalLensFingerprint? {
        val focal = metadata.focalLengthMillimetres?.toDouble()?.takeIf(::positiveFinite) ?: return null
        val hasGeometry = metadata.sensorPhysicalWidthMillimetres != null &&
            metadata.sensorPhysicalHeightMillimetres != null || metadata.pixelArray != null || metadata.activeArray != null
        if (!hasGeometry) return null
        val identity = buildString {
            append("optical-lens-v4")
            append("|facing=").append(metadata.facing.name)
            append("|focal=").append(decimal(quantize(focal, FOCAL_FINGERPRINT_STEP_MM)))
            append("|physical=")
            if (metadata.sensorPhysicalWidthMillimetres != null && metadata.sensorPhysicalHeightMillimetres != null) {
                append(decimal(quantize(metadata.sensorPhysicalWidthMillimetres.toDouble(), SENSOR_FINGERPRINT_STEP_MM)))
                append('x')
                append(decimal(quantize(metadata.sensorPhysicalHeightMillimetres.toDouble(), SENSOR_FINGERPRINT_STEP_MM)))
            } else {
                append('?')
            }
            append("|pixel=").append(metadata.pixelArray?.let(::geometryKey) ?: "?")
            append("|active=").append(metadata.activeArray?.let(::geometryKey) ?: "?")
            append("|orientation=").append(metadata.sensorOrientationDegrees ?: "?")
            append("|cfa=").append(metadata.colorFilterArrangement ?: "?")
        }
        return CanonicalLensFingerprint("lens:optical:${stableHash(identity)}")
    }

    fun fallbackFingerprint(
        environment: CameraEnvironmentFingerprint,
        profiles: Collection<CameraProfileFingerprint>,
    ): CanonicalLensFingerprint {
        val profileKey = profiles.map { it.value }.sorted().joinToString("|")
        return CanonicalLensFingerprint(
            "lens:fallback:${stableHash("optical-fallback-v4|${environment.value}|$profileKey")}",
        )
    }

    fun resolve(topology: CameraTopologySnapshot, lens: CanonicalLens): CanonicalLensOpticalMetadata {
        val routeKeys = lens.profiles.map { profile ->
            profile.route.openCameraId to profile.route.physicalCameraId
        }.toSet()
        return merge(topology.evidence.filter { evidence ->
            routeKeys.any { (transport, physical) ->
                evidence.transportId == transport && evidence.physicalId == physical
            }
        })
    }

    private fun chooseFocal(values: List<CameraMetadataEvidence>): Float? {
        val focals = values.flatMap { it.focalLengthsMillimetres }
            .map(Float::toDouble)
            .filter(::positiveFinite)
            .distinct()
            .sorted()
        if (focals.isEmpty()) return null
        if (relativeDelta(focals.first(), focals.last()) > FOCAL_CONSENSUS_RELATIVE_DELTA) return null
        return focals.median()?.toFloat()
    }

    private fun chooseSensor(values: List<CameraMetadataEvidence>): Pair<Float, Float>? {
        val sensors = values.mapNotNull { evidence ->
            val width = evidence.sensorPhysicalWidthMillimetres?.toDouble()
            val height = evidence.sensorPhysicalHeightMillimetres?.toDouble()
            if (width != null && height != null && positiveFinite(width) && positiveFinite(height)) {
                width to height
            } else {
                null
            }
        }
        if (sensors.isEmpty()) return null
        val winning = sensors.groupBy { (width, height) ->
            "${decimal(quantize(width, SENSOR_VOTE_STEP_MM))}x${decimal(quantize(height, SENSOR_VOTE_STEP_MM))}"
        }.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Pair<Double, Double>>>> { it.value.size }
                .thenBy { it.key },
        ).first().value
        val width = winning.map { it.first }.sorted().median() ?: return null
        val height = winning.map { it.second }.sorted().median() ?: return null
        return quantize(width, SENSOR_FINGERPRINT_STEP_MM).toFloat() to
            quantize(height, SENSOR_FINGERPRINT_STEP_MM).toFloat()
    }

    private fun chooseGeometry(values: List<IntSize>): IntSize? {
        if (values.isEmpty()) return null
        val grouped = values.groupBy { size ->
            val width = quantizePixels(size.width)
            val height = quantizePixels(size.height)
            width to height
        }
        val winner = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<Pair<Int, Int>, List<IntSize>>> { it.value.size }
                .thenBy { it.key.first }
                .thenBy { it.key.second },
        ).first().key
        return IntSize(winner.first.coerceAtLeast(1), winner.second.coerceAtLeast(1))
    }

    private fun chooseFacing(values: List<LensFacing>): LensFacing {
        val known = values.filterNot { it == LensFacing.UNKNOWN }
        if (known.isEmpty()) return LensFacing.UNKNOWN
        val ranked = known.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<LensFacing, Int>> { it.value }.thenBy { it.key.ordinal })
        if (ranked.size > 1 && ranked[0].value == ranked[1].value) return LensFacing.UNKNOWN
        return ranked.first().key
    }

    private fun <T : Comparable<T>> chooseKnown(values: List<T>): T? {
        if (values.isEmpty()) return null
        val ranked = values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<T, Int>> { it.value }.thenBy { it.key })
        if (ranked.size > 1 && ranked[0].value == ranked[1].value) return null
        return ranked.first().key
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val middle = size / 2
        return if (size % 2 == 1) this[middle] else (this[middle - 1] + this[middle]) / 2.0
    }

    private fun quantize(value: Double, step: Double): Double =
        BigDecimal.valueOf(value / step)
            .setScale(0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(step))
            .toDouble()

    private fun quantizePixels(value: Int): Int =
        ((value + GEOMETRY_STEP_PIXELS / 2) / GEOMETRY_STEP_PIXELS) * GEOMETRY_STEP_PIXELS

    private fun geometryKey(value: IntSize): String = "${value.width}x${value.height}"

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun positiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0

    private fun relativeDelta(left: Double, right: Double): Double =
        abs(left - right) / max(abs(left), abs(right)).coerceAtLeast(1e-9)

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
