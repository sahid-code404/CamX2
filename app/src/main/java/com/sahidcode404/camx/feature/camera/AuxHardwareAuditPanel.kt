package com.sahidcode404.camx.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sahidcode404.camx.core.camera.bootstrap.LensInventoryStatus
import com.sahidcode404.camx.core.camera.cache.TopologyCacheMigrationAudit
import com.sahidcode404.camx.core.camera.diagnostics.AuxHardwareAuditSnapshot

@Composable
internal fun AuxHardwareAuditPanel(
    audit: AuxHardwareAuditSnapshot,
    inventoryStatus: LensInventoryStatus? = null,
    onClose: () -> Unit,
    onDeepRescan: () -> Unit,
    onResetDiscoveryCache: () -> Unit,
) {
    val counters = audit.counters
    val switch = audit.switch
    val cache = TopologyCacheMigrationAudit.snapshot()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AUX Hardware Audit", color = Color.White)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDeepRescan) { Text("Deep Rescan") }
            Button(onClick = onResetDiscoveryCache) { Text("Reset Discovery Cache") }
        }
        audit.deepRescanResult?.let { Text("Deep rescan: ${it.name}", color = Color.White) }
        audit.cacheResetResult?.let { Text("Cache reset: ${it.name}", color = Color.White) }

        inventoryStatus?.let { inventory ->
            Text("Lens inventory", color = Color.White)
            auditLine("Inventory readiness", inventory.readiness.name)
            auditLine("Inventory source", inventory.source?.name ?: "none")
            auditLine("Structural publications", inventory.structuralPublicationCount)
            timingLine("Inventory ready latency", inventory.inventoryReadyLatencyMs)
            timingLine("Last structural replacement", inventory.lastStructuralReplacementLatencyMs)
            timingLine("Last refresh completion", inventory.lastRefreshCompletionLatencyMs)
            auditLine("Last refresh outcome", inventory.lastRefreshOutcome?.name ?: "none")
        }

        Text("Lens switch", color = Color.White)
        timingLine("Tap → accepted", switch.tapToAcceptedMs)
        timingLine("Tap → cleanup complete", switch.tapToCleanupCompleteMs)
        timingLine("Tap → open requested", switch.tapToOpenRequestedMs)
        timingLine("Tap → camera opened", switch.tapToCameraOpenedMs)
        timingLine("Tap → session configured", switch.tapToSessionConfiguredMs)
        timingLine("Tap → first frame", switch.tapToFirstFrameMs)
        timingLine("Tap → PreviewVerified", switch.tapToPreviewVerifiedMs)
        auditLine("Superseded intents", switch.supersededIntentCount)
        auditLine("Actual opens", switch.actualOpenCount)
        auditLine("Transient retries", switch.transientRetryCount)
        auditLine("Fallback to last verified", switch.fallbackToLastVerifiedCount)

        Text("Topology cache", color = Color.White)
        auditLine("Current topology schema", cache.currentTopologySchema)
        auditLine("Stored topology schema", cache.storedTopologySchema ?: "none")
        auditLine("Cache status", cache.status)
        auditLine("Cache migrated this launch", cache.migrated)
        auditLine(
            "Environment compatible",
            cache.environmentCompatible?.let { if (it) "yes" else "no" } ?: "n/a",
        )

        Text("Discovery pipeline", color = Color.White)
        auditLine("Java advertised IDs", counters.javaAdvertisedIds)
        auditLine("JAVA_PUBLIC evidence", counters.javaPublicEvidence)
        auditLine("Logical cameras", counters.logicalCameraCount)
        auditLine("Physical relationships", counters.physicalMemberRelationships)
        auditLine("Physical metadata successes", counters.physicalMetadataSuccesses)
        auditLine("Physical metadata failures", counters.physicalMetadataFailures)
        auditLine("NDK advertised evidence", counters.ndkAdvertisedEvidence)
        auditLine("Deep candidates attempted", counters.deepCandidateAddressesAttempted)
        auditLine("Deep VALID_METADATA", counters.deepValidMetadata)
        auditLine("Deep terminal negatives", counters.deepTerminalNegative)
        auditLine("Deep access denied", counters.deepAccessDenied)
        auditLine("Deep temporary/service failures", counters.deepTemporaryOrServiceFailure)
        auditLine("JAVA_DEEP_PROBED attempts", counters.javaDeepCertificationAttempts)
        auditLine("JAVA_DEEP_PROBED certified", counters.javaDeepCertified)
        counters.javaDeepCertificationFailuresByType.forEach { (kind, count) ->
            auditLine("Java deep failure $kind", count)
        }
        auditLine("Resolved routes", audit.resolvedRoutes)
        auditLine("Resolved profiles", audit.resolvedProfiles)
        auditLine("Canonical lenses", audit.canonicalLenses)
        auditLine("Selectable canonical lenses", audit.selectableCanonicalLenses)
        auditLine("Nonselectable canonical lenses", audit.nonselectableCanonicalLenses)
        auditLine("SESSION_VERIFIED lenses", audit.sessionVerifiedLenses)
        auditLine("Incremental topology publications", counters.incrementalTopologyPublications)

        Text("Timing", color = Color.White)
        timingLine("First frame → Level-2", counters.firstFrameToLevel2FirstPublicationMs)
        timingLine("First frame → NDK deep valid", counters.firstFrameToFirstNdkDeepValidMs)
        timingLine("First frame → Java deep certified", counters.firstFrameToFirstJavaDeepCertificationMs)
        timingLine("First frame → new selectable lens", counters.firstFrameToFirstNewSelectableLensMs)
        timingLine("Full reconciliation", counters.fullDeepReconciliationDurationMs)

        Text("Canonical lenses / profiles", color = Color.White)
        audit.lenses.forEach { lens ->
            Text(
                "Lens ${lens.fingerprint} label=${lens.stableOpticalLabel ?: "n/a"} facing=${lens.facing} " +
                    "1x=${lens.stableOneXRelationship} status=${lens.verificationStatus} profiles=${lens.profileCount} " +
                    "preferred=${lens.preferredProfile ?: "none"}",
                color = Color.White,
            )
            Text("aggregateTrust=${lens.aggregateTrust} ${lens.opticalMetadata}", color = Color.LightGray)
            lens.groupingReasons.forEach { reason ->
                Text("  $reason", color = Color.Gray)
            }
            lens.profiles.forEach { profile ->
                Text(
                    "  Profile ${profile.fingerprint} ${profile.routeKind} route=${profile.routeIdentity} " +
                        "relationship=${profile.logicalPhysicalRelationship ?: "none"} selectable=${profile.selectable} " +
                        "reject=${profile.rejectionReason ?: "none"} structural=${profile.structurallyFailed}",
                    color = Color.LightGray,
                )
                Text(
                    "    sources=${profile.provenance.joinToString(",")} " +
                        "trust=${profile.metadataTrust}/${profile.previewTrust} session=${profile.sessionTrust} " +
                        "previewSupport=${profile.previewSupported}",
                    color = Color.Gray,
                )
            }
        }

        if (audit.separationReasons.isNotEmpty()) {
            Text("Canonical separation reasons", color = Color.White)
            audit.separationReasons.forEach { reason ->
                Text(reason, color = Color.Gray)
            }
        }

        Text("Deep candidate pipeline", color = Color.White)
        audit.deepCandidates.forEach { candidate ->
            Text(
                "${candidate.fingerprint}: ndk=${candidate.ndkOutcome} java=${candidate.javaCertification ?: "not-certified"} " +
                    "route=${candidate.routeResolved} selectable=${candidate.profileSelectable} verified=${candidate.previewVerified} " +
                    "stage=${candidate.pipelineStage}",
                color = Color.LightGray,
            )
        }
    }
}

@Composable
private fun auditLine(label: String, value: Any) {
    Text("$label: $value", color = Color.LightGray)
}

@Composable
private fun timingLine(label: String, value: Long?) {
    Text("$label: ${value?.let { "$it ms" } ?: "n/a"}", color = Color.LightGray)
}
