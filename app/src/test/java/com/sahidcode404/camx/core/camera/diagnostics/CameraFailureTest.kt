package com.sahidcode404.camx.core.camera.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFailureTest {
    @Test
    fun storageCannotChangeRouteTrustOrTriggerFailover() {
        val policy = MediaStoreFailure("disk full").policy
        assertFalse(policy.structural)
        assertFalse(policy.sameCanonicalFailoverPermitted)
        assertTrue(policy.trustChange == TrustChange.NONE)
    }

    @Test
    fun requestedFpsAndExactRangeRejectionsPermitFallbackWithoutTrustDamage() {
        val failures = listOf(
            RequestedConfigurationRejected(RequestedConfigurationKind.FPS),
            RequestedConfigurationRejected(RequestedConfigurationKind.EXACT_FPS_RANGE),
        )

        failures.forEach { failure ->
            assertFalse(failure.policy.structural)
            assertTrue(failure.policy.fallbackPermitted)
            assertFalse(failure.policy.sameCanonicalFailoverPermitted)
            assertEquals(TrustChange.NONE, failure.policy.trustChange)
        }
    }

    @Test
    fun optionalStreamAndEnhancementRejectionsLeaveSafeBaselineAvailable() {
        val optionalRequests = listOf(
            RequestedConfigurationKind.HIGH_RESOLUTION_PREVIEW,
            RequestedConfigurationKind.OPTIONAL_YUV_OUTPUT,
            RequestedConfigurationKind.OPTIONAL_ANALYSIS_OUTPUT,
            RequestedConfigurationKind.OPTIONAL_AUXILIARY_STREAM,
            RequestedConfigurationKind.ASPECT_PREFERENCE,
            RequestedConfigurationKind.ENHANCEMENT,
        )

        optionalRequests.forEach { requested ->
            val policy = RequestedConfigurationRejected(requested).policy
            assertFalse(policy.structural)
            assertTrue(policy.fallbackPermitted)
            assertEquals(TrustChange.NONE, policy.trustChange)
        }
    }

    @Test
    fun safeBaselineFailureMayRejectPreviewAndFailOverSameCanonical() {
        val policy = SafeBaselineConfigurationRejected.policy
        assertTrue(policy.structural)
        assertFalse(policy.fallbackPermitted)
        assertTrue(policy.sameCanonicalFailoverPermitted)
        assertTrue(policy.trustChange == TrustChange.REJECT_PREVIEW_PROFILE)
    }

    @Test
    fun rawStructuralFailureStillChangesOnlyRawTrust() {
        val policy = RawSessionRejected.policy
        assertTrue(policy.structural)
        assertFalse(policy.fallbackPermitted)
        assertEquals(TrustChange.REJECT_RAW_PROFILE, policy.trustChange)
    }
}
