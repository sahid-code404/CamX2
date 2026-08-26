package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.lens.CameraLensProjectionInput
import com.sahidcode404.camx.core.camera.lens.CameraLensUiProjector
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import java.util.Collections

internal data class LensInventoryStructuralEntry(
    val canonicalFingerprint: CanonicalLensFingerprint,
    val facing: LensFacing,
    val primaryLabel: String,
    val secondaryOpticalLabel: String?,
)

internal data class LensInventoryStructuralSignature(
    val stableOneXReference: CanonicalLensFingerprint?,
    val entries: List<LensInventoryStructuralEntry>,
)

/**
 * Stable normal-selector structure only. Session status, trust transitions, profile preference, and
 * provider ordering are intentionally excluded so they cannot manufacture a structural UI publish.
 */
internal object LensInventoryStructuralSignatureResolver {
    fun resolve(
        topology: CameraTopologySnapshot,
        runtimeApiLevel: Int,
        stableOneXReference: CanonicalLensFingerprint?,
    ): LensInventoryStructuralSignature {
        val projection = CameraLensUiProjector.project(
            CameraLensProjectionInput(
                topology = trustNeutralTopology(topology),
                runtimeApiLevel = runtimeApiLevel,
                activeSelection = null,
                stableOneXReferenceFingerprint = stableOneXReference,
            ),
        )
        return LensInventoryStructuralSignature(
            stableOneXReference = stableOneXReference,
            entries = Collections.unmodifiableList(
                projection.items.map { item ->
                    LensInventoryStructuralEntry(
                        canonicalFingerprint = item.canonicalFingerprint,
                        facing = item.facing,
                        primaryLabel = item.primaryLabel,
                        secondaryOpticalLabel = item.secondaryOpticalLabel,
                    )
                },
            ),
        )
    }

    /**
     * Trust is runtime/session evidence, not canonical selector structure. Normalizing it here keeps
     * OPENING/VERIFIED/temporary/structural route-state churn out of the structural signature while
     * retaining capabilities, Java authority, orientation, optics, facing, and API eligibility.
     */
    fun trustNeutralTopology(topology: CameraTopologySnapshot): CameraTopologySnapshot {
        val routes = topology.routes.map { route ->
            route.copy(
                metadataTrust = CameraTrust.ADVERTISED,
                previewTrust = PreviewTrust.ADVERTISED,
            )
        }
        val routeById = routes.associateBy { it.id }
        val lenses = topology.canonicalLenses.map { lens ->
            CanonicalLens(
                fingerprint = lens.fingerprint,
                facing = lens.facing,
                profiles = lens.profiles.map { profile ->
                    CameraProfile(
                        fingerprint = profile.fingerprint,
                        canonicalFingerprint = profile.canonicalFingerprint,
                        route = checkNotNull(routeById[profile.route.id]),
                    )
                },
            )
        }
        return CameraTopologySnapshot(
            schema = topology.schema,
            environment = topology.environment,
            routes = routes,
            canonicalLenses = lenses,
            generatedAtElapsedRealtimeNs = topology.generatedAtElapsedRealtimeNs,
            evidence = topology.evidence,
        )
    }
}
