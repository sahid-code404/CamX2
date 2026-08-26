package com.sahidcode404.camx.core.camera.model

import java.util.Collections

enum class CameraRouteSource {
    JAVA_PUBLIC,
    JAVA_PHYSICAL,
    NDK_ADVERTISED,
    NDK_DEEP,
    JAVA_DEEP_PROBED,
}

enum class LensFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN,
}

data class IntSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Size dimensions must be positive" }
    }

    val area: Long = width.toLong() * height.toLong()
}

data class CameraStreamCapability(
    val type: PreviewStreamType,
    val size: IntSize,
    val minimumFrameDurationNs: Long?,
) {
    init {
        require(type != PreviewStreamType.AUTO) {
            "AUTO is a selection policy, not an advertised camera stream type"
        }
        require(minimumFrameDurationNs == null || minimumFrameDurationNs >= 0L) {
            "Minimum frame duration cannot be negative"
        }
    }
}

data class CameraFpsCapability(
    val minimum: Int,
    val maximum: Int,
) {
    init {
        require(minimum > 0) { "Minimum FPS must be positive" }
        require(maximum >= minimum) { "Maximum FPS must be at least minimum FPS" }
    }
}

data class CameraCapabilities(
    val previewStreams: List<CameraStreamCapability> = emptyList(),
    val fpsRanges: List<CameraFpsCapability> = emptyList(),
    val rawSizes: List<IntSize> = emptyList(),
)

data class CameraMetadataEvidence(
    val source: CameraRouteSource,
    val transportId: CameraTransportId,
    val physicalId: PhysicalCameraId? = null,
    val logicalParentId: CameraTransportId? = null,
    val facing: LensFacing = LensFacing.UNKNOWN,
    val focalLengthsMillimetres: List<Float> = emptyList(),
    val sensorPhysicalWidthMillimetres: Float? = null,
    val sensorPhysicalHeightMillimetres: Float? = null,
    val activeArray: IntSize? = null,
    val pixelArray: IntSize? = null,
    val sensorOrientationDegrees: Int? = null,
    val apertureValues: List<Float> = emptyList(),
    val colorFilterArrangement: Int? = null,
    val capabilities: CameraCapabilities = CameraCapabilities(),
) {
    init {
        require(focalLengthsMillimetres.all { it.isFinite() && it > 0f }) {
            "Focal lengths must be finite and positive"
        }
        require(apertureValues.all { it.isFinite() && it > 0f }) {
            "Apertures must be finite and positive"
        }
        require(sensorPhysicalWidthMillimetres == null ||
            sensorPhysicalWidthMillimetres.isFinite() && sensorPhysicalWidthMillimetres > 0f
        ) { "Sensor width must be finite and positive" }
        require(sensorPhysicalHeightMillimetres == null ||
            sensorPhysicalHeightMillimetres.isFinite() && sensorPhysicalHeightMillimetres > 0f
        ) { "Sensor height must be finite and positive" }
        require(sensorOrientationDegrees == null ||
            sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0
        ) { "Sensor orientation must be null or one of 0, 90, 180, or 270 degrees" }
    }
}

data class CameraRoute(
    val id: CameraRouteId,
    val source: CameraRouteSource,
    val openCameraId: CameraTransportId,
    val physicalCameraId: PhysicalCameraId? = null,
    val capabilities: CameraCapabilities,
    val metadataTrust: CameraTrust,
    val previewTrust: PreviewTrust = PreviewTrust.UNKNOWN,
    val rawTrust: RawTrust = RawTrust.UNKNOWN,
    val sources: Set<CameraRouteSource> = setOf(source),
) {
    init {
        require(sources.isNotEmpty() && source in sources) {
            "Route provenance must include its primary source"
        }
    }
}

data class CameraProfile(
    val fingerprint: CameraProfileFingerprint,
    val canonicalFingerprint: CanonicalLensFingerprint,
    val route: CameraRoute,
)

data class CanonicalLens(
    val fingerprint: CanonicalLensFingerprint,
    val facing: LensFacing,
    val profiles: List<CameraProfile>,
) {
    init {
        require(profiles.isNotEmpty()) { "Canonical lens must contain at least one profile" }
        require(profiles.all { it.canonicalFingerprint == fingerprint }) {
            "Every profile must belong to this canonical lens"
        }
        require(profiles.map { it.fingerprint }.distinct().size == profiles.size) {
            "Canonical lens contains duplicate profile fingerprints"
        }
        require(profiles.map { it.route.id }.distinct().size == profiles.size) {
            "Canonical lens contains duplicate routes"
        }
    }
}

data class ActiveCameraSelection(
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val profileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
)

data class CameraTopologySnapshot(
    val schema: Int,
    val environment: CameraEnvironmentFingerprint,
    val routes: List<CameraRoute>,
    val canonicalLenses: List<CanonicalLens>,
    val generatedAtElapsedRealtimeNs: Long,
    val evidence: List<CameraMetadataEvidence> = emptyList(),
) {
    init {
        require(schema > 0) { "Topology schema must be positive" }
        require(generatedAtElapsedRealtimeNs >= 0L) { "Topology timestamp cannot be negative" }
        val routeIds = routes.map { it.id }
        require(routeIds.distinct().size == routeIds.size) { "Topology contains duplicate route IDs" }
        val lensIds = canonicalLenses.map { it.fingerprint }
        require(lensIds.distinct().size == lensIds.size) {
            "Topology contains duplicate canonical lens fingerprints"
        }
        val profiles = canonicalLenses.flatMap { it.profiles }
        require(profiles.map { it.fingerprint }.distinct().size == profiles.size) {
            "Topology contains duplicate profile fingerprints"
        }
        require(profiles.map { it.route.id }.distinct().size == profiles.size) {
            "A topology route cannot belong to more than one canonical lens"
        }
        require(profiles.all { profile -> profile.route.id in routeIds }) {
            "Topology profile references an absent route"
        }
        require(profiles.map { it.route.id }.toSet() == routeIds.toSet()) {
            "Every topology route must belong to exactly one canonical lens"
        }
    }
}

/** Creates an unaliased, unmodifiable publication value from possibly mutable input collections. */
internal fun CameraTopologySnapshot.frozenCopy(): CameraTopologySnapshot {
    val frozenRoutes = immutableList(routes.map(CameraRoute::frozenCopy))
    val routesById = frozenRoutes.associateBy(CameraRoute::id)
    val frozenLenses = immutableList(canonicalLenses.map { lens ->
        CanonicalLens(
            fingerprint = lens.fingerprint,
            facing = lens.facing,
            profiles = immutableList(lens.profiles.map { profile ->
                CameraProfile(
                    fingerprint = profile.fingerprint,
                    canonicalFingerprint = profile.canonicalFingerprint,
                    route = checkNotNull(routesById[profile.route.id]) {
                        "Profile references absent route ${profile.route.id.value}"
                    },
                )
            }),
        )
    })
    return CameraTopologySnapshot(
        schema = schema,
        environment = environment,
        routes = frozenRoutes,
        canonicalLenses = frozenLenses,
        generatedAtElapsedRealtimeNs = generatedAtElapsedRealtimeNs,
        evidence = immutableList(evidence.map(CameraMetadataEvidence::frozenCopy)),
    )
}

internal fun CameraRoute.frozenCopy(): CameraRoute = copy(
    capabilities = capabilities.frozenCopy(),
    sources = immutableSet(sources),
)

internal fun CameraMetadataEvidence.frozenCopy(): CameraMetadataEvidence = copy(
    focalLengthsMillimetres = immutableList(focalLengthsMillimetres),
    apertureValues = immutableList(apertureValues),
    capabilities = capabilities.frozenCopy(),
)

internal fun CameraCapabilities.frozenCopy(): CameraCapabilities = copy(
    previewStreams = immutableList(previewStreams),
    fpsRanges = immutableList(fpsRanges),
    rawSizes = immutableList(rawSizes),
)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
