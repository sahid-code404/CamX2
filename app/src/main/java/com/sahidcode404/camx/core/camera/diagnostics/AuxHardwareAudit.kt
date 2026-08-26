package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.cache.DiscoveryCacheResetResult
import com.sahidcode404.camx.core.camera.discovery.DeepAuxOutcomeKind
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationKind
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.NdkDeepEvidenceReport
import com.sahidcode404.camx.core.camera.lens.CameraLensProjection
import com.sahidcode404.camx.core.camera.lens.CanonicalLensTrustAggregator
import com.sahidcode404.camx.core.camera.lens.LensProfileEligibility
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.topology.CanonicalLensOptics
import com.sahidcode404.camx.core.camera.topology.OpticalLensMatch
import com.sahidcode404.camx.core.camera.topology.OpticalLensMatcher
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuxDiscoveryPipelineCounters(
    val javaAdvertisedIds: Int = 0,
    val javaPublicEvidence: Int = 0,
    val logicalCameraCount: Int = 0,
    val physicalMemberRelationships: Int = 0,
    val physicalMetadataSuccesses: Int = 0,
    val physicalMetadataFailures: Int = 0,
    val ndkAdvertisedEvidence: Int = 0,
    val deepCandidateAddressesAttempted: Int = 0,
    val deepValidMetadata: Int = 0,
    val deepTerminalNegative: Int = 0,
    val deepAccessDenied: Int = 0,
    val deepTemporaryOrServiceFailure: Int = 0,
    val javaDeepCertificationAttempts: Int = 0,
    val javaDeepCertified: Int = 0,
    val javaDeepCertificationFailuresByType: Map<String, Int> = emptyMap(),
    val firstFrameToLevel2FirstPublicationMs: Long? = null,
    val firstFrameToFirstNdkDeepValidMs: Long? = null,
    val firstFrameToFirstJavaDeepCertificationMs: Long? = null,
    val firstFrameToFirstNewSelectableLensMs: Long? = null,
    val fullDeepReconciliationDurationMs: Long? = null,
    val incrementalTopologyPublications: Long = 0L,
)

data class AuxCacheAudit(
    val currentTopologySchema: Int = CameraSchemaVersions.TOPOLOGY,
    val storedTopologySchema: Int? = null,
    val status: String = "NOT_CHECKED",
    val environmentCompatible: Boolean? = null,
    val migrated: Boolean = false,
)

data class AuxProfileAudit(
    val fingerprint: String,
    val provenance: List<String>,
    val routeKind: String,
    val routeIdentity: String,
    val logicalPhysicalRelationship: String?,
    val metadataTrust: String,
    val previewTrust: String,
    val sessionTrust: String,
    val previewSupported: Boolean,
    val javaPublic: Boolean,
    val javaPhysical: Boolean,
    val javaDeepProbed: Boolean,
    val ndkAdvertised: Boolean,
    val ndkDeep: Boolean,
    val selectable: Boolean,
    val rejectionReason: String?,
    val structurallyFailed: Boolean,
)

data class AuxLensAudit(
    val fingerprint: String,
    val facing: String,
    val stableOpticalLabel: String?,
    val stableOneXRelationship: String,
    val opticalMetadata: String,
    val profileCount: Int,
    val preferredProfile: String?,
    val aggregateTrust: String,
    val verificationStatus: String,
    val groupingReasons: List<String>,
    val profiles: List<AuxProfileAudit>,
)

data class AuxDeepCandidateAudit(
    val fingerprint: String,
    val plannedWave: String?,
    val ndkOutcome: String?,
    val javaCertification: String?,
    val routeResolved: Boolean,
    val profileSelectable: Boolean,
    val sessionStatus: String?,
    val previewVerified: Boolean,
    val pipelineStage: String,
)

data class AuxHardwareAuditSnapshot(
    val counters: AuxDiscoveryPipelineCounters = AuxDiscoveryPipelineCounters(),
    val cache: AuxCacheAudit = AuxCacheAudit(),
    val switch: LensSwitchDiagnostics = LensSwitchDiagnostics(),
    val resolvedRoutes: Int = 0,
    val resolvedProfiles: Int = 0,
    val canonicalLenses: Int = 0,
    val selectableCanonicalLenses: Int = 0,
    val nonselectableCanonicalLenses: Int = 0,
    val sessionVerifiedLenses: Int = 0,
    val lenses: List<AuxLensAudit> = emptyList(),
    val separationReasons: List<String> = emptyList(),
    val deepCandidates: List<AuxDeepCandidateAudit> = emptyList(),
    val deepRescanResult: DeepRescanRequestResult? = null,
    val cacheResetResult: DiscoveryCacheResetResult? = null,
)

internal data class AuxDiscoveryTrackerSnapshot(
    val counters: AuxDiscoveryPipelineCounters,
    val plannedDeepCandidates: Map<String, String>,
    val deepOutcomes: Map<String, DeepAuxOutcomeKind>,
    val javaCertification: Map<String, JavaDeepCertificationKind>,
    val deepRescanResult: DeepRescanRequestResult?,
    val cacheResetResult: DiscoveryCacheResetResult?,
)

/** Bounded in-memory diagnostics only. Raw opaque IDs never leave this internal tracker. */
internal class AuxDiscoveryAuditTracker(
    private val clockNanos: () -> Long,
) {
    private val changesMutable = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changesMutable.asStateFlow()

    private val javaAdvertisedIds = LinkedHashSet<String>()
    private val javaPublicEvidence = LinkedHashSet<String>()
    private val logicalParents = LinkedHashSet<String>()
    private val physicalRelationships = LinkedHashSet<String>()
    private val physicalMetadataSuccesses = LinkedHashSet<String>()
    private val physicalMetadataFailures = LinkedHashSet<String>()
    private val ndkAdvertisedEvidence = LinkedHashSet<String>()
    private val plannedDeepCandidates = LinkedHashMap<String, String>()
    private val deepOutcomes = LinkedHashMap<String, DeepAuxOutcomeKind>()
    private val javaCertification = LinkedHashMap<String, JavaDeepCertificationKind>()

    private var firstFrameNs: Long? = null
    private var runStartedNs: Long? = null
    private var baselineSelectable = 0
    private var baselinePublicationCount = 0L
    private var level2FirstPublicationNs: Long? = null
    private var firstNdkDeepValidNs: Long? = null
    private var firstJavaDeepCertificationNs: Long? = null
    private var firstNewSelectableNs: Long? = null
    private var runFinishedNs: Long? = null
    private var latestPublicationCount = 0L
    private var deepRescanResult: DeepRescanRequestResult? = null
    private var cacheResetResult: DiscoveryCacheResetResult? = null

    @Synchronized
    fun markFirstFrame(atNanos: Long = clockNanos()) {
        if (firstFrameNs == null) firstFrameNs = atNanos.coerceAtLeast(0L)
        changed()
    }

    @Synchronized
    fun beginRun(selectableCount: Int, publicationCount: Long) {
        javaAdvertisedIds.clear()
        javaPublicEvidence.clear()
        logicalParents.clear()
        physicalRelationships.clear()
        physicalMetadataSuccesses.clear()
        physicalMetadataFailures.clear()
        ndkAdvertisedEvidence.clear()
        plannedDeepCandidates.clear()
        deepOutcomes.clear()
        javaCertification.clear()
        runStartedNs = clockNanos().coerceAtLeast(0L)
        runFinishedNs = null
        level2FirstPublicationNs = null
        firstNdkDeepValidNs = null
        firstJavaDeepCertificationNs = null
        firstNewSelectableNs = null
        baselineSelectable = selectableCount.coerceAtLeast(0)
        baselinePublicationCount = publicationCount.coerceAtLeast(0L)
        latestPublicationCount = baselinePublicationCount
        changed()
    }

    @Synchronized
    fun onJavaAdvertised(report: JavaAdvertisedEvidenceReport) {
        markLevel2Publication()
        report.snapshots.forEach { snapshot ->
            snapshot.evidence.forEach { evidence ->
                val address = address(evidence.source, evidence.transportId.value, evidence.physicalId?.value)
                if (evidence.source == CameraRouteSource.JAVA_PUBLIC) {
                    javaAdvertisedIds += evidence.transportId.value
                    javaPublicEvidence += address
                }
                if (evidence.source == CameraRouteSource.JAVA_PHYSICAL) {
                    evidence.logicalParentId?.value?.let(logicalParents::add)
                    physicalRelationships += address
                    if (hasPhysicalMetadata(evidence)) physicalMetadataSuccesses += address
                }
            }
        }
        report.failures.forEach { failure ->
            if (failure.physicalId == null) {
                failure.transportId?.let(javaAdvertisedIds::add)
            } else {
                physicalMetadataFailures +=
                    "${failure.transportId.orEmpty()}|${failure.physicalId}|${failure.kind.name}"
                failure.transportId?.let(logicalParents::add)
            }
        }
        changed()
    }

    @Synchronized
    fun onNdkAdvertised(report: NdkAdvertisedEvidenceReport) {
        markLevel2Publication()
        report.snapshot.evidence.forEach { evidence ->
            ndkAdvertisedEvidence += address(evidence.source, evidence.transportId.value, evidence.physicalId?.value)
        }
        changed()
    }

    @Synchronized
    fun onNdkDeep(report: NdkDeepEvidenceReport) {
        report.plannedCandidates.forEach { candidate ->
            plannedDeepCandidates[candidate.transportId] = candidate.wave.name
        }
        report.outcomes.forEach { outcome ->
            deepOutcomes[outcome.candidate.transportId] = outcome.outcome
            if (outcome.outcome == DeepAuxOutcomeKind.VALID_METADATA && firstNdkDeepValidNs == null) {
                firstNdkDeepValidNs = clockNanos().coerceAtLeast(0L)
            }
        }
        changed()
    }

    @Synchronized
    fun onJavaDeep(report: JavaDeepCertificationReport) {
        report.outcomes.forEach { outcome ->
            javaCertification[outcome.candidate.transportId] = outcome.kind
            if (outcome.kind == JavaDeepCertificationKind.CERTIFIED && firstJavaDeepCertificationNs == null) {
                firstJavaDeepCertificationNs = clockNanos().coerceAtLeast(0L)
            }
        }
        changed()
    }

    @Synchronized
    fun onTopologyState(selectableCount: Int, publicationCount: Long) {
        latestPublicationCount = maxOf(latestPublicationCount, publicationCount)
        if (selectableCount > baselineSelectable && firstNewSelectableNs == null && runStartedNs != null) {
            firstNewSelectableNs = clockNanos().coerceAtLeast(0L)
        }
        changed()
    }

    @Synchronized
    fun finishRun() {
        runFinishedNs = clockNanos().coerceAtLeast(0L)
        changed()
    }

    @Synchronized
    fun recordDeepRescanResult(result: DeepRescanRequestResult) {
        deepRescanResult = result
        changed()
    }

    @Synchronized
    fun recordCacheResetResult(result: DiscoveryCacheResetResult) {
        cacheResetResult = result
        changed()
    }

    @Synchronized
    fun snapshot(): AuxDiscoveryTrackerSnapshot {
        val terminal = deepOutcomes.values.count { outcome ->
            outcome == DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE ||
                outcome == DeepAuxOutcomeKind.INVALID_OPERATION ||
                outcome == DeepAuxOutcomeKind.MALFORMED_METADATA ||
                outcome == DeepAuxOutcomeKind.BOUND_EXCEEDED
        }
        val temporary = deepOutcomes.values.count { outcome ->
            outcome == DeepAuxOutcomeKind.SERVICE_ERROR ||
                outcome == DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE ||
                outcome == DeepAuxOutcomeKind.RUNTIME_UNAVAILABLE
        }
        val certificationFailures = javaCertification.values
            .filter { it != JavaDeepCertificationKind.CERTIFIED }
            .groupingBy { it.name }
            .eachCount()
            .toSortedMap()
        val counters = AuxDiscoveryPipelineCounters(
            javaAdvertisedIds = javaAdvertisedIds.size,
            javaPublicEvidence = javaPublicEvidence.size,
            logicalCameraCount = logicalParents.size,
            physicalMemberRelationships = physicalRelationships.size,
            physicalMetadataSuccesses = physicalMetadataSuccesses.size,
            physicalMetadataFailures = physicalMetadataFailures.size,
            ndkAdvertisedEvidence = ndkAdvertisedEvidence.size,
            deepCandidateAddressesAttempted = deepOutcomes.size,
            deepValidMetadata = deepOutcomes.values.count { it == DeepAuxOutcomeKind.VALID_METADATA },
            deepTerminalNegative = terminal,
            deepAccessDenied = deepOutcomes.values.count { it == DeepAuxOutcomeKind.ACCESS_DENIED },
            deepTemporaryOrServiceFailure = temporary,
            javaDeepCertificationAttempts = javaCertification.size,
            javaDeepCertified = javaCertification.values.count { it == JavaDeepCertificationKind.CERTIFIED },
            javaDeepCertificationFailuresByType = Collections.unmodifiableMap(LinkedHashMap(certificationFailures)),
            firstFrameToLevel2FirstPublicationMs = elapsedFromFirstFrame(level2FirstPublicationNs),
            firstFrameToFirstNdkDeepValidMs = elapsedFromFirstFrame(firstNdkDeepValidNs),
            firstFrameToFirstJavaDeepCertificationMs = elapsedFromFirstFrame(firstJavaDeepCertificationNs),
            firstFrameToFirstNewSelectableLensMs = elapsedFromFirstFrame(firstNewSelectableNs),
            fullDeepReconciliationDurationMs = elapsed(runStartedNs, runFinishedNs),
            incrementalTopologyPublications = (latestPublicationCount - baselinePublicationCount).coerceAtLeast(0L),
        )
        return AuxDiscoveryTrackerSnapshot(
            counters = counters,
            plannedDeepCandidates = Collections.unmodifiableMap(LinkedHashMap(plannedDeepCandidates)),
            deepOutcomes = Collections.unmodifiableMap(LinkedHashMap(deepOutcomes)),
            javaCertification = Collections.unmodifiableMap(LinkedHashMap(javaCertification)),
            deepRescanResult = deepRescanResult,
            cacheResetResult = cacheResetResult,
        )
    }

    private fun markLevel2Publication() {
        if (level2FirstPublicationNs == null) level2FirstPublicationNs = clockNanos().coerceAtLeast(0L)
    }

    private fun elapsedFromFirstFrame(eventNs: Long?): Long? = elapsed(firstFrameNs, eventNs)

    private fun elapsed(startNs: Long?, endNs: Long?): Long? {
        if (startNs == null || endNs == null || endNs < startNs) return null
        return (endNs - startNs) / 1_000_000L
    }

    private fun address(source: CameraRouteSource, id: String, physical: String?): String =
        "${source.name}|$id|${physical.orEmpty()}"

    private fun hasPhysicalMetadata(evidence: CameraMetadataEvidence): Boolean =
        evidence.focalLengthsMillimetres.isNotEmpty() ||
            evidence.sensorPhysicalWidthMillimetres != null ||
            evidence.sensorPhysicalHeightMillimetres != null ||
            evidence.activeArray != null ||
            evidence.pixelArray != null ||
            evidence.sensorOrientationDegrees != null ||
            evidence.apertureValues.isNotEmpty() ||
            evidence.colorFilterArrangement != null ||
            evidence.capabilities.previewStreams.isNotEmpty() ||
            evidence.capabilities.fpsRanges.isNotEmpty() ||
            evidence.capabilities.rawSizes.isNotEmpty()

    private fun changed() {
        val current = changesMutable.value
        changesMutable.value = if (current == Long.MAX_VALUE) 0L else current + 1L
    }
}

/** Pure projection of internal discovery state into sanitized, deterministic hardware-audit output. */
internal object AuxHardwareAudit {
    private const val MAX_GROUPING_REASONS_PER_LENS = 8
    private const val MAX_SEPARATION_REASONS = 24

    fun build(
        topology: CameraTopologySnapshot?,
        projection: CameraLensProjection,
        tracker: AuxDiscoveryTrackerSnapshot,
        cache: AuxCacheAudit = AuxCacheAudit(),
        switch: LensSwitchDiagnostics = LensSwitchDiagnostics(),
    ): AuxHardwareAuditSnapshot {
        if (topology == null) {
            return AuxHardwareAuditSnapshot(
                counters = tracker.counters,
                cache = cache,
                switch = switch,
                deepRescanResult = tracker.deepRescanResult,
                cacheResetResult = tracker.cacheResetResult,
            )
        }
        val itemByLens = projection.items.associateBy { it.canonicalFingerprint }
        val lenses = topology.canonicalLenses.sortedBy { it.fingerprint.value }.map { lens ->
            val profiles = lens.profiles.sortedBy { it.fingerprint.value }.map { profile ->
                profileAudit(topology, profile, projection)
            }
            val preferred = projection.targets[lens.fingerprint]?.profileFingerprint?.value
            val hasSessionVerifiedProfile = lens.profiles.any { it.route.previewTrust == PreviewTrust.VERIFIED }
            val item = itemByLens[lens.fingerprint]
            val currentStatus = item?.status
            val trust = CanonicalLensTrustAggregator.aggregate(lens)
            val stableLabel = item?.let { ui ->
                listOfNotNull(ui.primaryLabel.takeIf(String::isNotBlank), ui.secondaryOpticalLabel)
                    .joinToString(" / ")
                    .takeIf(String::isNotBlank)
            }
            AuxLensAudit(
                fingerprint = sanitized("lens", lens.fingerprint.value),
                facing = lens.facing.name,
                stableOpticalLabel = stableLabel,
                stableOneXRelationship = when {
                    lens.facing != LensFacing.BACK -> "NOT_REAR"
                    lens.fingerprint == projection.stableOneXReferenceFingerprint -> "REFERENCE_1X"
                    item != null -> item.primaryLabel
                    else -> "UNAVAILABLE"
                },
                opticalMetadata = opticalMetadata(topology, lens),
                profileCount = lens.profiles.size,
                preferredProfile = preferred?.let { sanitized("profile", it) },
                aggregateTrust = "${trust.metadataTrust.name}/${trust.previewTrust.name}/${trust.rawTrust.name}",
                verificationStatus = currentStatus?.name ?: if (hasSessionVerifiedProfile) {
                    "SESSION_VERIFIED"
                } else {
                    "DIAGNOSTIC_ONLY"
                },
                groupingReasons = Collections.unmodifiableList(
                    ArrayList(groupingReasons(topology, lens).take(MAX_GROUPING_REASONS_PER_LENS)),
                ),
                profiles = Collections.unmodifiableList(ArrayList(profiles)),
            )
        }
        val candidateIds = (tracker.plannedDeepCandidates.keys +
            tracker.deepOutcomes.keys + tracker.javaCertification.keys).distinct().sorted()
        val deepCandidates = candidateIds.map { id ->
            val candidateProfiles = topology.canonicalLenses.asSequence()
                .flatMap { lens -> lens.profiles.asSequence().map { lens to it } }
                .filter { (_, profile) -> profile.route.openCameraId.value == id }
                .toList()
            val routeResolved = candidateProfiles.isNotEmpty()
            val profileSelectable = candidateProfiles.any { (_, profile) ->
                projection.eligibilityByProfile[profile.fingerprint] is LensProfileEligibility.Eligible
            }
            val selectedCandidate = candidateProfiles.firstOrNull { (lens, profile) ->
                projection.targets[lens.fingerprint]?.profileFingerprint == profile.fingerprint
            }
            val sessionStatus = selectedCandidate?.first?.let { lens -> itemByLens[lens.fingerprint]?.status?.name }
            val previewVerified = sessionStatus == LensTestStatus.VERIFIED.name
            val ndkOutcome = tracker.deepOutcomes[id]
            val javaCertification = tracker.javaCertification[id]
            val pipelineStage = when {
                ndkOutcome == null -> "PLANNED_NOT_ATTEMPTED"
                ndkOutcome != DeepAuxOutcomeKind.VALID_METADATA -> "NDK_${ndkOutcome.name}"
                javaCertification == null -> "NDK_VALID_JAVA_NOT_ATTEMPTED"
                javaCertification != JavaDeepCertificationKind.CERTIFIED -> "NDK_VALID_JAVA_CERTIFICATION_FAILED"
                !routeResolved -> "JAVA_CERTIFIED_RESOLVER_REJECTED"
                !profileSelectable -> "ROUTE_CREATED_PROFILE_REJECTED"
                selectedCandidate == null -> "PROFILE_ELIGIBLE_NOT_PREFERRED"
                sessionStatus == LensTestStatus.FAILED.name -> "LENS_SELECTABLE_SESSION_FAILED"
                sessionStatus == LensTestStatus.OPENING.name -> "LENS_SELECTABLE_OPENING"
                previewVerified -> "LENS_PREVIEW_VERIFIED"
                else -> "LENS_SELECTABLE_UNVERIFIED"
            }
            AuxDeepCandidateAudit(
                fingerprint = sanitized("deep", id),
                plannedWave = tracker.plannedDeepCandidates[id],
                ndkOutcome = ndkOutcome?.name,
                javaCertification = javaCertification?.name,
                routeResolved = routeResolved,
                profileSelectable = profileSelectable,
                sessionStatus = sessionStatus,
                previewVerified = previewVerified,
                pipelineStage = pipelineStage,
            )
        }
        val profiles = topology.canonicalLenses.sumOf { it.profiles.size }
        val verified = topology.canonicalLenses.count { lens ->
            itemByLens[lens.fingerprint]?.status == LensTestStatus.VERIFIED ||
                lens.profiles.any { it.route.previewTrust == PreviewTrust.VERIFIED }
        }
        return AuxHardwareAuditSnapshot(
            counters = tracker.counters,
            cache = cache,
            switch = switch,
            resolvedRoutes = topology.routes.size,
            resolvedProfiles = profiles,
            canonicalLenses = topology.canonicalLenses.size,
            selectableCanonicalLenses = projection.items.size,
            nonselectableCanonicalLenses = (topology.canonicalLenses.size - projection.items.size).coerceAtLeast(0),
            sessionVerifiedLenses = verified,
            lenses = Collections.unmodifiableList(ArrayList(lenses)),
            separationReasons = Collections.unmodifiableList(
                ArrayList(separationReasons(topology).take(MAX_SEPARATION_REASONS)),
            ),
            deepCandidates = Collections.unmodifiableList(ArrayList(deepCandidates)),
            deepRescanResult = tracker.deepRescanResult,
            cacheResetResult = tracker.cacheResetResult,
        )
    }

    private fun profileAudit(
        topology: CameraTopologySnapshot,
        profile: CameraProfile,
        projection: CameraLensProjection,
    ): AuxProfileAudit {
        val route = profile.route
        val eligibility = projection.eligibilityByProfile[profile.fingerprint]
        val rejection = (eligibility as? LensProfileEligibility.Rejected)?.reason
        val evidence = evidenceForProfile(topology, profile)
        val relationships = evidence.mapNotNull { item ->
            val member = item.physicalId?.value ?: return@mapNotNull null
            val parent = item.logicalParentId?.value ?: item.transportId.value
            "${sanitized("parent", parent)}→${sanitized("member", member)}"
        }.distinct().sorted()
        return AuxProfileAudit(
            fingerprint = sanitized("profile", profile.fingerprint.value),
            provenance = route.sources.map { it.name }.sorted(),
            routeKind = if (route.physicalCameraId == null) "DIRECT" else "PHYSICAL_TARGET",
            routeIdentity = sanitized(
                "route",
                "${route.source.name}|${route.openCameraId.value}|${route.physicalCameraId?.value.orEmpty()}",
            ),
            logicalPhysicalRelationship = relationships.joinToString(",").takeIf(String::isNotBlank),
            metadataTrust = route.metadataTrust.name,
            previewTrust = route.previewTrust.name,
            sessionTrust = route.previewTrust.name,
            previewSupported = route.capabilities.previewStreams.isNotEmpty(),
            javaPublic = CameraRouteSource.JAVA_PUBLIC in route.sources,
            javaPhysical = CameraRouteSource.JAVA_PHYSICAL in route.sources,
            javaDeepProbed = CameraRouteSource.JAVA_DEEP_PROBED in route.sources,
            ndkAdvertised = CameraRouteSource.NDK_ADVERTISED in route.sources,
            ndkDeep = CameraRouteSource.NDK_DEEP in route.sources,
            selectable = eligibility is LensProfileEligibility.Eligible,
            rejectionReason = rejection?.name,
            structurallyFailed = rejection?.name == "STRUCTURALLY_FAILED_PROFILE",
        )
    }

    private fun opticalMetadata(topology: CameraTopologySnapshot, lens: CanonicalLens): String {
        val optical = CanonicalLensOptics.resolve(topology, lens)
        val focal = optical.focalLengthMillimetres?.let { "${it}mm" } ?: "?"
        val sensor = if (
            optical.sensorPhysicalWidthMillimetres != null && optical.sensorPhysicalHeightMillimetres != null
        ) {
            "${optical.sensorPhysicalWidthMillimetres}x${optical.sensorPhysicalHeightMillimetres}mm"
        } else {
            "?"
        }
        val active = optical.activeArray?.let { "${it.width}x${it.height}" } ?: "?"
        val pixel = optical.pixelArray?.let { "${it.width}x${it.height}" } ?: "?"
        return "focal=$focal sensor=$sensor active=$active pixel=$pixel " +
            "orientation=${optical.sensorOrientationDegrees ?: "?"} cfa=${optical.colorFilterArrangement ?: "?"}"
    }

    private fun groupingReasons(topology: CameraTopologySnapshot, lens: CanonicalLens): List<String> {
        val profiles = lens.profiles.sortedBy { it.fingerprint.value }
        val reasons = ArrayList<String>()
        for (leftIndex in profiles.indices) {
            for (rightIndex in leftIndex + 1 until profiles.size) {
                val left = profiles[leftIndex]
                val right = profiles[rightIndex]
                val comparison = OpticalLensMatcher.compare(
                    evidenceForProfile(topology, left),
                    evidenceForProfile(topology, right),
                )
                reasons += comparisonLine("GROUPED", left, right, comparison)
            }
        }
        return reasons
    }

    private fun separationReasons(topology: CameraTopologySnapshot): List<String> {
        val lenses = topology.canonicalLenses.sortedBy { it.fingerprint.value }
        val reasons = ArrayList<String>()
        for (leftIndex in lenses.indices) {
            for (rightIndex in leftIndex + 1 until lenses.size) {
                if (reasons.size >= MAX_SEPARATION_REASONS) return reasons
                val leftLens = lenses[leftIndex]
                val rightLens = lenses[rightIndex]
                val comparisons = leftLens.profiles.flatMap { left ->
                    rightLens.profiles.map { right ->
                        Triple(
                            left,
                            right,
                            OpticalLensMatcher.compare(
                                evidenceForProfile(topology, left),
                                evidenceForProfile(topology, right),
                            ),
                        )
                    }
                }
                val selected = comparisons.minWithOrNull(
                    compareBy<Triple<CameraProfile, CameraProfile, com.sahidcode404.camx.core.camera.topology.OpticalLensComparison>>(
                        { comparisonRank(it.third.match) },
                        { it.first.fingerprint.value },
                        { it.second.fingerprint.value },
                    ),
                ) ?: continue
                val lensPair = "${sanitized("lens", leftLens.fingerprint.value)}↔" +
                    sanitized("lens", rightLens.fingerprint.value)
                reasons += "$lensPair ${comparisonLine("SEPARATE", selected.first, selected.second, selected.third)}"
            }
        }
        return reasons
    }

    private fun comparisonRank(match: OpticalLensMatch): Int = when (match) {
        OpticalLensMatch.CONFLICT -> 0
        OpticalLensMatch.INSUFFICIENT_EVIDENCE -> 1
        OpticalLensMatch.PROBABLE_MATCH -> 2
        OpticalLensMatch.STRONG_MATCH -> 3
    }

    private fun comparisonLine(
        prefix: String,
        left: CameraProfile,
        right: CameraProfile,
        comparison: com.sahidcode404.camx.core.camera.topology.OpticalLensComparison,
    ): String {
        val families = comparison.evidenceFamilies.map { it.name }.sorted().joinToString(",").ifBlank { "none" }
        val details = (comparison.positiveReasons.take(2) + comparison.negativeReasons.take(2))
            .joinToString("; ")
            .ifBlank { "no decisive evidence" }
        return "$prefix profiles=${sanitized("profile", left.fingerprint.value)}↔" +
            "${sanitized("profile", right.fingerprint.value)} match=${comparison.match.name} " +
            "score=${comparison.score} families=$families reason=$details"
    }

    private fun evidenceForProfile(
        topology: CameraTopologySnapshot,
        profile: CameraProfile,
    ): List<CameraMetadataEvidence> = topology.evidence.filter { evidence ->
        evidence.transportId == profile.route.openCameraId &&
            evidence.physicalId == profile.route.physicalCameraId
    }

    private fun sanitized(kind: String, value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("aux-audit|$kind|$value".toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        return buildString(16) {
            repeat(8) { index ->
                val byte = bytes[index].toInt() and 0xff
                append(alphabet[byte ushr 4])
                append(alphabet[byte and 0x0f])
            }
        }
    }
}
