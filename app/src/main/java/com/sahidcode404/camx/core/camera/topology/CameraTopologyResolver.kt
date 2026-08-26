package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.frozenCopy
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** Pure, bounded, deterministic and deliberately conservative topology resolver. */
object CameraTopologyResolver {
    const val SCHEMA = CameraSchemaVersions.TOPOLOGY

    const val MAX_TOTAL_EVIDENCE = 256
    const val MAX_ROUTES = 128
    const val MAX_PROFILES = 128
    const val MAX_CANONICAL_LENSES = 64
    const val MAX_PROFILES_PER_LENS = 32
    const val MAX_PROVENANCE_SOURCES = 5
    const val MAX_FOCAL_LENGTHS = 16
    const val MAX_APERTURES = 16
    const val MAX_PREVIEW_STREAMS = 128
    const val MAX_FPS_RANGES = 64
    const val MAX_RAW_SIZES = 64

    fun resolve(
        environment: CameraEnvironmentFingerprint,
        snapshots: List<CameraEvidenceSnapshot>,
        generatedAtElapsedRealtimeNs: Long,
        previousTrustedTopology: CameraTopologySnapshot? = null,
    ): CameraTopologySnapshot {
        require(generatedAtElapsedRealtimeNs >= 0L) { "Topology timestamp cannot be negative" }
        validateInputBounds(environment, snapshots)

        val evidence = snapshots
            .asSequence()
            .flatMap { it.evidence.asSequence() }
            .map(CameraMetadataEvidence::frozenCopy)
            .sortedWith(compareBy({ it.opaqueOrderKey() }, { it.deterministicKey() }))
            .distinct()
            .toList()

        val compatiblePreviousTopology = previousTrustedTopology
            ?.takeIf { previous ->
                previous.environment == environment &&
                    previous.schema == SCHEMA &&
                    previous.isWithinTopologyBounds()
            }
        val previousRoutesById = compatiblePreviousTopology
            ?.routes
            ?.associateBy(CameraRoute::id)
            .orEmpty()
        val previousEvidenceByTransport = compatiblePreviousTopology
            ?.evidence
            ?.groupBy { it.transportKey() }
            .orEmpty()

        // Stage A: exact transport profile identity. Provider/source differences do not create sibling
        // profiles. Direct identity is the open transport; physical identity is logical parent + member.
        val routesWithEvidence = ArrayList<Pair<CameraRoute, List<CameraMetadataEvidence>>>()
        val transportGroups = evidence
            .groupBy { it.transportKey() }
            .entries
            .sortedWith(compareBy({ opaqueKey(it.key.transportId) }, { opaqueKey(it.key.physicalId.orEmpty()) }))
        for ((transportKey, values) in transportGroups) {
            if (!clusterCanFormRoute(values)) continue
            val preferred = values.minWithOrNull(
                compareBy<CameraMetadataEvidence>({ it.sourcePriority() }, { it.deterministicKey() }),
            ) ?: continue
            val advertised = CameraRoute(
                id = CameraRouteId("route:${stableHash(routeIdentity(transportKey))}"),
                source = preferred.source,
                openCameraId = preferred.transportId,
                physicalCameraId = preferred.physicalId,
                capabilities = mergeCapabilities(values.map { it.capabilities }),
                metadataTrust = CameraTrust.ADVERTISED,
                sources = values.map { it.source }.toSet(),
            )
            require(advertised.sources.size <= MAX_PROVENANCE_SOURCES) {
                "Route provenance exceeds the CAMX-107 bound"
            }
            val previousEvidence = previousEvidenceByTransport[transportKey].orEmpty()
            val previous = previousRoutesById[advertised.id]
                ?.takeIf { old ->
                    old.openCameraId == advertised.openCameraId &&
                        old.physicalCameraId == advertised.physicalCameraId &&
                        previousEvidence.isNotEmpty() &&
                        previousEvidence.all { oldEvidence ->
                            values.all { current -> !metadataConflicts(current, oldEvidence) }
                        }
                }
            val route = if (previous == null) advertised else advertised.copy(
                metadataTrust = previous.metadataTrust,
                previewTrust = previous.previewTrust,
                rawTrust = previous.rawTrust,
            )
            routesWithEvidence += route to values.distinct()
            require(routesWithEvidence.size <= MAX_ROUTES) {
                "Resolved routes exceed the CAMX-107 bound"
            }
        }

        val candidates = routesWithEvidence.map { (route, routeEvidence) ->
            ProfileCandidate(
                profile = CameraProfile(
                    fingerprint = CameraProfileFingerprint("profile:${stableHash(route.id.value)}"),
                    canonicalFingerprint = CanonicalLensFingerprint("candidate:${stableHash(route.id.value)}"),
                    route = route,
                ),
                evidence = routeEvidence,
            )
        }
        require(candidates.size <= MAX_PROFILES) { "Resolved profiles exceed the CAMX-107 bound" }

        // Stage B: transport-independent optical grouping. Complete-link is intentional: every new
        // profile must strongly match every existing member, so an A↔B↔C transitive chain cannot
        // collapse two ambiguous endpoints into one physical lens.
        val groups = completeLinkGroups(candidates)
        require(groups.size <= MAX_CANONICAL_LENSES) {
            "Resolved canonical lenses exceed the CAMX-107 bound"
        }
        groups.forEach { group ->
            require(group.size <= MAX_PROFILES_PER_LENS) {
                "Profiles per canonical lens exceed the CAMX-107 bound"
            }
        }

        val canonicalGroups = groups.map { group ->
            val metadata = CanonicalLensOptics.merge(group.flatMap { it.evidence })
            CanonicalGroup(
                profiles = group,
                metadata = metadata,
                stableFingerprint = CanonicalLensOptics.stableFingerprint(metadata),
                fallbackFingerprint = CanonicalLensOptics.fallbackFingerprint(
                    environment = environment,
                    profiles = group.map { it.profile.fingerprint },
                ),
            )
        }
        val stableFingerprintCounts = canonicalGroups.mapNotNull { it.stableFingerprint }
            .groupingBy { it }
            .eachCount()
        val canonicalLenses = canonicalGroups.map { group ->
            // A stable optical key is usable only when it uniquely names one complete-link group.
            // If sparse evidence makes two independent groups collide, fall back rather than merge.
            val fingerprint = group.stableFingerprint
                ?.takeIf { stableFingerprintCounts[it] == 1 }
                ?: group.fallbackFingerprint
            val profiles = group.profiles
                .map { it.profile.copy(canonicalFingerprint = fingerprint) }
                .sortedBy { it.fingerprint.value }
            CanonicalLens(
                fingerprint = fingerprint,
                facing = group.metadata.facing,
                profiles = profiles,
            )
        }.sortedBy { it.fingerprint.value }

        return CameraTopologySnapshot(
            schema = SCHEMA,
            environment = environment,
            routes = routesWithEvidence.map { it.first }.sortedBy { it.id.value },
            canonicalLenses = canonicalLenses,
            generatedAtElapsedRealtimeNs = generatedAtElapsedRealtimeNs,
            evidence = evidence,
        ).frozenCopy()
    }

    private fun completeLinkGroups(candidates: List<ProfileCandidate>): List<List<ProfileCandidate>> {
        val groups = ArrayList<MutableList<ProfileCandidate>>()
        val ordered = candidates.sortedBy { it.profile.fingerprint.value }
        for (candidate in ordered) {
            val eligible = groups.mapIndexedNotNull { index, group ->
                val comparisons = group.map { member ->
                    OpticalLensMatcher.compare(member.evidence, candidate.evidence)
                }
                if (comparisons.all { it.match == OpticalLensMatch.STRONG_MATCH }) {
                    GroupFit(index, comparisons.minOfOrNull { it.score } ?: Int.MIN_VALUE, groupKey(group))
                } else {
                    null
                }
            }
            val selected = eligible.sortedWith(
                compareByDescending<GroupFit> { it.weakestScore }
                    .thenBy { it.groupKey }
                    .thenBy { it.index },
            ).firstOrNull()
            if (selected == null) groups += arrayListOf(candidate) else groups[selected.index] += candidate
        }
        return groups
            .map { it.sortedBy { candidate -> candidate.profile.fingerprint.value } }
            .sortedBy(::groupKey)
    }

    private fun groupKey(group: List<ProfileCandidate>): String = group
        .map { it.profile.fingerprint.value }
        .sorted()
        .joinToString("|")

    private fun validateInputBounds(
        environment: CameraEnvironmentFingerprint,
        snapshots: List<CameraEvidenceSnapshot>,
    ) {
        require(snapshots.all { it.environment == environment }) {
            "Cannot combine evidence from different camera environments"
        }
        require(snapshots.map { it.source }.distinct().size <= MAX_PROVENANCE_SOURCES) {
            "Evidence provenance exceeds the CAMX-107 bound"
        }
        var totalEvidence = 0
        snapshots.forEach { snapshot ->
            require(snapshot.evidence.size <= MAX_TOTAL_EVIDENCE - totalEvidence) {
                "Total evidence exceeds the CAMX-107 bound"
            }
            totalEvidence += snapshot.evidence.size
            snapshot.evidence.forEach(::validateEvidenceBounds)
        }
    }

    private fun validateEvidenceBounds(item: CameraMetadataEvidence) {
        require(item.focalLengthsMillimetres.size <= MAX_FOCAL_LENGTHS) {
            "Focal-length evidence exceeds the CAMX-107 bound"
        }
        require(item.apertureValues.size <= MAX_APERTURES) {
            "Aperture evidence exceeds the CAMX-107 bound"
        }
        require(item.capabilities.previewStreams.size <= MAX_PREVIEW_STREAMS) {
            "Preview-stream evidence exceeds the CAMX-107 bound"
        }
        require(item.capabilities.fpsRanges.size <= MAX_FPS_RANGES) {
            "FPS-range evidence exceeds the CAMX-107 bound"
        }
        require(item.capabilities.rawSizes.size <= MAX_RAW_SIZES) {
            "RAW-size evidence exceeds the CAMX-107 bound"
        }
    }

    private fun CameraTopologySnapshot.isWithinTopologyBounds(): Boolean {
        if (routes.size > MAX_ROUTES || canonicalLenses.size > MAX_CANONICAL_LENSES ||
            evidence.size > MAX_TOTAL_EVIDENCE
        ) return false
        val profiles = canonicalLenses.flatMap { it.profiles }
        return profiles.size <= MAX_PROFILES &&
            canonicalLenses.all { it.profiles.size <= MAX_PROFILES_PER_LENS } &&
            routes.all { route ->
                route.sources.size <= MAX_PROVENANCE_SOURCES &&
                    route.capabilities.previewStreams.size <= MAX_PREVIEW_STREAMS &&
                    route.capabilities.fpsRanges.size <= MAX_FPS_RANGES &&
                    route.capabilities.rawSizes.size <= MAX_RAW_SIZES
            }
    }

    private data class TransportKey(val transportId: String, val physicalId: String?)

    private data class ProfileCandidate(
        val profile: CameraProfile,
        val evidence: List<CameraMetadataEvidence>,
    )

    private data class CanonicalGroup(
        val profiles: List<ProfileCandidate>,
        val metadata: CanonicalLensOpticalMetadata,
        val stableFingerprint: CanonicalLensFingerprint?,
        val fallbackFingerprint: CanonicalLensFingerprint,
    )

    private data class GroupFit(
        val index: Int,
        val weakestScore: Int,
        val groupKey: String,
    )

    private fun CameraMetadataEvidence.transportKey(): TransportKey {
        val parent = if (physicalId != null) logicalParentId?.value ?: transportId.value else transportId.value
        return TransportKey(parent, physicalId?.value)
    }

    private fun routeIdentity(key: TransportKey): String {
        val physical = key.physicalId ?: return "${key.transportId}|"
        val transportBytes = key.transportId.toByteArray(Charsets.UTF_8).size
        val physicalBytes = physical.toByteArray(Charsets.UTF_8).size
        return "physical:$transportBytes:${key.transportId}:$physicalBytes:$physical"
    }

    private fun clusterCanFormRoute(cluster: List<CameraMetadataEvidence>): Boolean {
        if (cluster.any {
                it.source == CameraRouteSource.JAVA_PUBLIC ||
                    it.source == CameraRouteSource.JAVA_PHYSICAL ||
                    it.source == CameraRouteSource.NDK_ADVERTISED
            }
        ) return true
        val certified = cluster.filter { it.source == CameraRouteSource.JAVA_DEEP_PROBED }
        return certified.any { evidence ->
            evidence.sensorOrientationDegrees != null &&
                evidence.focalLengthsMillimetres.isNotEmpty() &&
                evidence.capabilities.previewStreams.any { it.type == PreviewStreamType.CAMERA2_PRIVATE } &&
                evidence.capabilities.fpsRanges.isNotEmpty()
        }
    }

    private fun CameraMetadataEvidence.deterministicKey(): String = buildString {
        append(opaqueKey(transportId.value))
        append('|')
        append(physicalId?.value?.let(::opaqueKey).orEmpty())
        append('|')
        append(logicalParentId?.value?.let(::opaqueKey).orEmpty())
        append('|')
        append(source.ordinal)
        append('|')
        append(facing.ordinal)
        append('|')
        append(floatListKey(focalLengthsMillimetres).joinToString(","))
        append('|')
        append(sensorPhysicalWidthMillimetres?.let(::floatKey).orEmpty())
        append('|')
        append(sensorPhysicalHeightMillimetres?.let(::floatKey).orEmpty())
        append('|')
        append(activeArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|')
        append(pixelArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|')
        append(sensorOrientationDegrees?.toString().orEmpty())
        append('|')
        append(floatListKey(apertureValues).joinToString(","))
        append('|')
        append(colorFilterArrangement?.toString().orEmpty())
        append('|')
        append(capabilities.previewStreams.sortedWith(
            compareBy({ it.type.ordinal }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs }),
        ).joinToString(",") { "${it.type}:${it.size.width}x${it.size.height}:${it.minimumFrameDurationNs}" })
        append('|')
        append(capabilities.fpsRanges.sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .joinToString(",") { "${it.minimum}-${it.maximum}" })
        append('|')
        append(capabilities.rawSizes.sortedWith(compareBy({ it.width }, { it.height }))
            .joinToString(",") { "${it.width}x${it.height}" })
    }

    private fun CameraMetadataEvidence.opaqueOrderKey(): String = buildString {
        append(opaqueKey(transportId.value))
        append('|')
        append(physicalId?.value?.let(::opaqueKey).orEmpty())
        append('|')
        append(source.ordinal)
    }

    private fun CameraMetadataEvidence.sourcePriority(): Int = when (source) {
        CameraRouteSource.JAVA_PHYSICAL -> 0
        CameraRouteSource.JAVA_PUBLIC -> 1
        CameraRouteSource.JAVA_DEEP_PROBED -> 2
        CameraRouteSource.NDK_ADVERTISED -> 3
        CameraRouteSource.NDK_DEEP -> 4
    }

    private fun mergeCapabilities(values: List<CameraCapabilities>): CameraCapabilities {
        val preview = values.flatMap { it.previewStreams }.distinct().sortedWith(
            compareBy({ it.type.ordinal }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs }),
        )
        val fps = values.flatMap { it.fpsRanges }.distinct().sortedWith(
            compareBy({ it.minimum }, { it.maximum }),
        )
        val raw = values.flatMap { it.rawSizes }.distinct().sortedWith(
            compareBy({ it.width }, { it.height }),
        )
        require(preview.size <= MAX_PREVIEW_STREAMS) { "Merged preview streams exceed the CAMX-107 bound" }
        require(fps.size <= MAX_FPS_RANGES) { "Merged FPS ranges exceed the CAMX-107 bound" }
        require(raw.size <= MAX_RAW_SIZES) { "Merged RAW sizes exceed the CAMX-107 bound" }
        return CameraCapabilities(previewStreams = preview, fpsRanges = fps, rawSizes = raw)
    }

    /** Only material contradictions invalidate previously verified route trust. */
    private fun metadataConflicts(left: CameraMetadataEvidence, right: CameraMetadataEvidence): Boolean {
        if (left.facing != LensFacing.UNKNOWN && right.facing != LensFacing.UNKNOWN && left.facing != right.facing) {
            return true
        }
        val leftFocal = left.focalLengthsMillimetres.singleOrNull()?.toDouble()
        val rightFocal = right.focalLengthsMillimetres.singleOrNull()?.toDouble()
        if (leftFocal != null && rightFocal != null && relativeDelta(leftFocal, rightFocal) > 0.035) return true
        if (relativeDeltaOrNull(
                left.sensorPhysicalWidthMillimetres?.toDouble(),
                right.sensorPhysicalWidthMillimetres?.toDouble(),
            )?.let { it > 0.06 } == true
        ) return true
        if (relativeDeltaOrNull(
                left.sensorPhysicalHeightMillimetres?.toDouble(),
                right.sensorPhysicalHeightMillimetres?.toDouble(),
            )?.let { it > 0.06 } == true
        ) return true
        if (left.colorFilterArrangement != null && right.colorFilterArrangement != null &&
            left.colorFilterArrangement != right.colorFilterArrangement
        ) return true
        return false
    }

    private fun relativeDeltaOrNull(left: Double?, right: Double?): Double? =
        if (left != null && right != null && left.isFinite() && right.isFinite() && left > 0.0 && right > 0.0) {
            relativeDelta(left, right)
        } else {
            null
        }

    private fun relativeDelta(left: Double, right: Double): Double =
        abs(left - right) / max(abs(left), abs(right)).coerceAtLeast(1e-9)

    private fun floatListKey(values: List<Float>): List<String> = values.sorted().map(::floatKey)

    private fun floatKey(value: Float): String = value.toRawBits().toUInt().toString(16).padStart(8, '0')

    private fun opaqueKey(value: String): String = "opaque:${stableHash("opaque-order|$value")}"

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        val output = CharArray(32)
        var cursor = 0
        repeat(16) { index ->
            val byte = digest[index].toInt() and 0xff
            output[cursor++] = alphabet[byte ushr 4]
            output[cursor++] = alphabet[byte and 0x0f]
        }
        return String(output)
    }
}
