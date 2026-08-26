package com.sahidcode404.camx.core.camera.runtime

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.diagnostics.TrustChange
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust

object SameCanonicalFailoverPolicy {
    fun nextProfile(
        topology: CameraTopologySnapshot,
        selectedCanonical: CanonicalLensFingerprint,
        activeProfile: CameraProfileFingerprint,
        attemptedProfiles: Set<CameraProfileFingerprint>,
        failure: CameraFailure,
    ): CameraProfile? {
        if (!failure.policy.sameCanonicalFailoverPermitted) return null
        val lens = topology.canonicalLenses.singleOrNull { it.fingerprint == selectedCanonical }
            ?: return null
        if (lens.profiles.none { it.fingerprint == activeProfile }) return null
        return lens.profiles
            .asSequence()
            .filterNot { it.fingerprint == activeProfile }
            .filterNot { it.fingerprint in attemptedProfiles }
            .filterNot { it.route.metadataTrust == CameraTrust.STRUCTURALLY_REJECTED }
            .filter { profile ->
                when (failure.policy.trustChange) {
                    TrustChange.REJECT_PREVIEW_PROFILE ->
                        profile.route.previewTrust != PreviewTrust.STRUCTURALLY_REJECTED
                    TrustChange.REJECT_RAW_PROFILE ->
                        profile.route.previewTrust != PreviewTrust.STRUCTURALLY_REJECTED &&
                            profile.route.rawTrust != RawTrust.STRUCTURALLY_REJECTED &&
                            profile.route.capabilities.rawSizes.isNotEmpty()
                    TrustChange.NONE,
                    TrustChange.MARK_TEMPORARILY_UNAVAILABLE,
                    -> true
                }
            }
            .sortedBy { it.fingerprint.value }
            .firstOrNull()
    }
}
