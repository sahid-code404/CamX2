package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint

/** Pure bounded A -> B failover decision. The coordinator owns the one-alternate transaction bound. */
internal object LensProfileFailoverPlanner {
    fun next(
        canonicalFingerprint: CanonicalLensFingerprint,
        failedProfile: CameraProfileFingerprint,
        attemptedProfiles: Set<CameraProfileFingerprint>,
        failure: CameraFailure,
        rankedEligibleTargets: List<LensSelectionTarget>,
    ): LensSelectionTarget? {
        if (!failure.policy.structural || !failure.policy.sameCanonicalFailoverPermitted) return null
        return rankedEligibleTargets.firstOrNull { target ->
            target.canonicalFingerprint == canonicalFingerprint &&
                target.profileFingerprint != failedProfile &&
                target.profileFingerprint !in attemptedProfiles
        }
    }
}
