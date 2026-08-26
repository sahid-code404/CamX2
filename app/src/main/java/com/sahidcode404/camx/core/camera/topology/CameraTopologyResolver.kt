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
import com.sahidcode404.camx.core.camera.model.frozenCopy
import java.security.MessageDigest

/** Pure, bounded, deterministic and deliberately conservative topology resolver. */
object CameraTopologyResolver {
    const val SCHEMA = CameraSchemaVersions.TOPOLOGY

    const val MAX_TOTAL_EVIDENCE = 256
    const val MAX_ROUTES = 128
    const val MAX_PROFILES = 128
    const val MAX_CANONICAL_LENSES = 64
    const val MAX_PROFILES_PER_LENS = 32
    const val MAX_PROVENANCE_SOURCES = 4
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

        // Freeze, normalize, order, and de-duplicate before identity work. Duplicate insertions therefore
        // cannot change a topology or its fingerprints.
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

        val routesWithEvidence = ArrayList<Pair<CameraRoute, List<CameraMetadataEvidence>>>()
        val transportGroups = evidence
            .groupBy { it.transportKey() }
            .entries
            .sortedWith(compareBy({ opaqueKey(it.key.transportId) }, { opaqueKey(it.key.physicalId.orEmpty()) }))

        for ((transportKey, values) in transportGroups) {
            val clusters = compatibilityClusters(values)
                .sortedWith(compareBy(
                    { cluster -> cluster.minOf { it.sourcePriority() } },
                    { cluster -> clusterFingerprint(cluster) },
                ))
            for ((clusterIndex, cluster) in clusters.withIndex()) {
                val normalIdentity = routeIdentity(transportKey)
                // A conflict on an otherwise identical transport must not silently inherit the old route
                // identity/trust. The direct path keeps the frozen CAMX-102 route-ID contract while
                // physical-member identity is length-encoded so opaque separators cannot collide.
                val routeIdentity = if (clusters.size == 1) {
                    normalIdentity
                } else {
                    "$normalIdentity|conflict|${clusterFingerprint(cluster)}|$clusterIndex"
                }
                val preferred = cluster.minBy { it.sourcePriority() }
                val advertised = CameraRoute(
                    id = CameraRouteId("route:${stableHash(routeIdentity)}"),
                    source = preferred.source,
                    openCameraId = preferred.transportId,
                    physicalCameraId = preferred.physicalId,
                    capabilities = mergeCapabilities(cluster.map { it.capabilities }),
                    metadataTrust = CameraTrust.ADVERTISED,
                    sources = cluster.map { it.source }.toSet(),
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
                                cluster.all { current -> !metadataConflicts(current, oldEvidence) }
                            }
                    }
                val route = if (previous == null) advertised else advertised.copy(
                    metadataTrust = previous.metadataTrust,
                    previewTrust = previous.previewTrust,
                    rawTrust = previous.rawTrust,
                )
                routesWithEvidence += route to cluster
                require(routesWithEvidence.size <= MAX_ROUTES) {
                    "Resolved routes exceed the CAMX-107 bound"
                }
            }
        }

        val relationshipPhysicalIds = evidence.mapNotNull { it.physicalId?.value }.toSet()
        val candidateProfiles = routesWithEvidence.map { (route, routeEvidence) ->
            val opticalKey = strongOpticalKey(routeEvidence)
            val relationshipAnchor = relationshipAnchor(routeEvidence, relationshipPhysicalIds)
            val canonicalKey = if (opticalKey != null && relationshipAnchor != null) {
                "related:${opaqueKey(relationshipAnchor)}|$opticalKey"
            } else {
                "separate:${route.id.value}"
            }
            CameraProfile(
                fingerprint = CameraProfileFingerprint("profile:${stableHash(route.id.value)}"),
                canonicalFingerprint = CanonicalLensFingerprint("lens:${stableHash(canonicalKey)}"),
                route = route,
            )
        }
        require(candidateProfiles.size <= MAX_PROFILES) {
            "Resolved profiles exceed the CAMX-107 bound"
        }

        val previousCanonicalByRoute = compatiblePreviousTopology
            ?.canonicalLenses
            ?.flatMap { lens -> lens.profiles.map { profile -> profile.route.id to lens.fingerprint } }
            ?.toMap()
            .orEmpty()
        val candidateGroups = candidateProfiles
            .groupBy(CameraProfile::canonicalFingerprint)
            .entries
            .sortedBy { it.key.value }
        require(candidateGroups.size <= MAX_CANONICAL_LENSES) {
            "Resolved canonical lenses exceed the CAMX-107 bound"
        }
        candidateGroups.forEach { (_, profiles) ->
            require(profiles.size <= MAX_PROFILES_PER_LENS) {
                "Profiles per canonical lens exceed the CAMX-107 bound"
            }
        }

        // Previous canonical identity may survive only for one current group and only when all current
        // route IDs still map unambiguously to that same previous lens. Conflict-split routes cannot match.
        val preservedCandidateByGroup = candidateGroups.associate { (candidate, groupedProfiles) ->
            candidate to groupedProfiles.mapNotNull { previousCanonicalByRoute[it.route.id] }.distinct()
        }
        val preservationUseCount = preservedCandidateByGroup.values
            .filter { it.size == 1 }
            .groupingBy { it.single() }
            .eachCount()
        val evidenceByRoute = routesWithEvidence.associate { (route, values) -> route.id to values }
        val canonicalLenses = candidateGroups.map { (candidateFingerprint, groupedProfiles) ->
            val previousCandidates = preservedCandidateByGroup.getValue(candidateFingerprint)
            val fingerprint = previousCandidates.singleOrNull()
                ?.takeIf { preservationUseCount[it] == 1 }
                ?: candidateFingerprint
            val stableProfiles = groupedProfiles.map { profile ->
                profile.copy(canonicalFingerprint = fingerprint)
            }
            val facings = groupedProfiles
                .flatMap { evidenceByRoute.getValue(it.route.id) }
                .map(CameraMetadataEvidence::facing)
                .filterNot { it == LensFacing.UNKNOWN }
                .distinct()
            CanonicalLens(
                fingerprint = fingerprint,
                facing = facings.singleOrNull() ?: LensFacing.UNKNOWN,
                profiles = stableProfiles.sortedBy { it.fingerprint.value },
            )
        }

        return CameraTopologySnapshot(
            schema = SCHEMA,
            environment = environment,
            routes = candidateProfiles.map(CameraProfile::route).sortedBy { it.id.value },
            canonicalLenses = canonicalLenses,
            generatedAtElapsedRealtimeNs = generatedAtElapsedRealtimeNs,
            evidence = evidence,
        ).frozenCopy()
    }

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
            "FPS evidence exceeds the CAMX-107 bound"
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

    private fun CameraMetadataEvidence.transportKey() = TransportKey(
        transportId = transportId.value,
        physicalId = physicalId?.value,
    )

    private fun routeIdentity(key: TransportKey): String {
        val physical = key.physicalId ?: return "${key.transportId}|"
        val transportBytes = key.transportId.toByteArray(Charsets.UTF_8).size
        val physicalBytes = physical.toByteArray(Charsets.UTF_8).size
        return "physical:$transportBytes:${key.transportId}:$physicalBytes:$physical"
    }

    private fun compatibilityClusters(
        values: List<CameraMetadataEvidence>,
    ): List<List<CameraMetadataEvidence>> {
        val clusters = ArrayList<MutableList<CameraMetadataEvidence>>()
        val ordered = values.sortedWith(compareBy({ it.opaqueOrderKey() }, { it.deterministicKey() }))
        for (item in ordered) {
            val target = clusters.firstOrNull { cluster ->
                cluster.all { existing -> !metadataConflicts(existing, item) }
            }
            if (target == null) clusters += arrayListOf(item) else target += item
        }
        return clusters.map { cluster -> cluster.distinct() }
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
        CameraRouteSource.NDK_ADVERTISED -> 2
        CameraRouteSource.NDK_DEEP -> 3
    }

    private fun clusterFingerprint(values: List<CameraMetadataEvidence>): String = stableHash(
        values.sortedBy { it.deterministicKey() }
            .joinToString("||") { it.deterministicKey() },
    )

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

    private fun strongOpticalKey(values: List<CameraMetadataEvidence>): String? {
        val complete = values.firstOrNull { item ->
            item.focalLengthsMillimetres.isNotEmpty() &&
                item.sensorPhysicalWidthMillimetres != null &&
                item.sensorPhysicalHeightMillimetres != null &&
                item.activeArray != null &&
                item.pixelArray != null &&
                item.sensorOrientationDegrees != null
        } ?: return null
        if (values.any { candidate -> metadataConflicts(complete, candidate) }) return null
        return buildString {
            append(complete.facing.name)
            append('|')
            append(floatListKey(complete.focalLengthsMillimetres).joinToString(","))
            append('|')
            append(floatKey(checkNotNull(complete.sensorPhysicalWidthMillimetres)))
            append('x')
            append(floatKey(checkNotNull(complete.sensorPhysicalHeightMillimetres)))
            append('|')
            append(complete.activeArray)
            append('|')
            append(complete.pixelArray)
            append('|')
            append(complete.colorFilterArrangement ?: "unknown")
            append('|')
            append(complete.sensorOrientationDegrees)
            append('|')
            append(floatListKey(complete.apertureValues).joinToString(","))
        }
    }

    private fun relationshipAnchor(
        values: List<CameraMetadataEvidence>,
        relationshipPhysicalIds: Set<String>,
    ): String? {
        val explicitPhysicalIds = values.mapNotNull { it.physicalId?.value }.distinct()
        if (explicitPhysicalIds.size == 1) return explicitPhysicalIds.single()
        if (explicitPhysicalIds.size > 1) return null
        val transportIds = values.map { it.transportId.value }.distinct()
        return transportIds.singleOrNull()?.takeIf { it in relationshipPhysicalIds }
    }

    /** Missing fields are compatible; contradictory fields are not. Focal length alone never merges lenses. */
    private fun metadataConflicts(
        left: CameraMetadataEvidence,
        right: CameraMetadataEvidence,
    ): Boolean {
        if (left.facing != LensFacing.UNKNOWN && right.facing != LensFacing.UNKNOWN && left.facing != right.facing) {
            return true
        }
        if (left.focalLengthsMillimetres.isNotEmpty() && right.focalLengthsMillimetres.isNotEmpty() &&
            floatListKey(left.focalLengthsMillimetres) != floatListKey(right.focalLengthsMillimetres)
        ) return true
        if (floatsConflict(left.sensorPhysicalWidthMillimetres, right.sensorPhysicalWidthMillimetres)) return true
        if (floatsConflict(left.sensorPhysicalHeightMillimetres, right.sensorPhysicalHeightMillimetres)) return true
        if (left.activeArray != null && right.activeArray != null && left.activeArray != right.activeArray) return true
        if (left.pixelArray != null && right.pixelArray != null && left.pixelArray != right.pixelArray) return true
        if (left.sensorOrientationDegrees != null && right.sensorOrientationDegrees != null &&
            left.sensorOrientationDegrees != right.sensorOrientationDegrees
        ) return true
        if (left.colorFilterArrangement != null && right.colorFilterArrangement != null &&
            left.colorFilterArrangement != right.colorFilterArrangement
        ) return true
        if (left.apertureValues.isNotEmpty() && right.apertureValues.isNotEmpty() &&
            floatListKey(left.apertureValues) != floatListKey(right.apertureValues)
        ) return true
        return false
    }

    private fun floatsConflict(left: Float?, right: Float?): Boolean =
        left != null && right != null && floatKey(left) != floatKey(right)

    private fun floatListKey(values: List<Float>): List<String> = values.sorted().map(::floatKey)

    /** Exact IEEE-754 representation; deliberately no decimal rounding or locale-sensitive formatting. */
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
