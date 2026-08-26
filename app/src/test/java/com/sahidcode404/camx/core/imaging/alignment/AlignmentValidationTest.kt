package com.sahidcode404.camx.core.imaging.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentValidationTest {
    @Test
    fun syntheticTruthPassesZeroErrorPercentileGate() {
        val first = alignedEvidence(2, 0)
        val second = alignedEvidence(-2, 2)
        val report = AlignmentTruthEvaluator.evaluate(
            AlignmentTruthCorpus(
                listOf(
                    AlignmentTruthCase(2, 0, first),
                    AlignmentTruthCase(-2, 2, second),
                ),
            ),
            thresholds(),
        )
        assertEquals(0.0, checkNotNull(report.p95TranslationErrorPixels), 0.0)
        assertEquals(0.0, report.catastrophicFraction, 0.0)
        assertTrue(report.accepted)
    }

    @Test
    fun excludedCaseCountsAsCatastrophicFailure() {
        val accepted = alignedEvidence(2, 0)
        val excluded = accepted.copy(decision = FrameAlignmentDecision.EXCLUDED_AMBIGUOUS)
        val report = AlignmentTruthEvaluator.evaluate(
            AlignmentTruthCorpus(
                listOf(
                    AlignmentTruthCase(2, 0, accepted),
                    AlignmentTruthCase(2, 0, excluded),
                ),
            ),
            thresholds(),
        )
        assertEquals(1, report.catastrophicCount)
        assertEquals(0.5, report.catastrophicFraction, 0.0)
        assertFalse(report.accepted)
    }

    private fun alignedEvidence(dx: Int, dy: Int) = AlignedFrameEvidence(
        ordinal = 1,
        translation = TranslationEstimate(dx, dy, 0.0, 1.0, 16),
        support = FrameSupportSummary(1.0, 1.0, 1.0, 0.0, 16, 16, 16, 16),
        rollingShutter = RollingShutterMotionEvidence(null, null, null),
        uncertainty = AlignmentUncertainty(0.0, 0.0, 0.0),
        decision = FrameAlignmentDecision.ACCEPTED,
    )

    private fun thresholds() = AlignmentTruthThresholds(
        maxP95TranslationErrorPixels = 0.0,
        maxP99TranslationErrorPixels = 0.0,
        catastrophicErrorPixels = 1.0,
        maxCatastrophicFraction = 0.0,
        minAcceptedFraction = 1.0,
    )
}
