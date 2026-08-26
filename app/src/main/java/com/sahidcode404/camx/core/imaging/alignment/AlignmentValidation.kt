package com.sahidcode404.camx.core.imaging.alignment

import java.util.Collections
import kotlin.math.hypot

class AlignmentTruthCorpus(cases: List<AlignmentTruthCase>) {
    val cases: List<AlignmentTruthCase> = Collections.unmodifiableList(ArrayList(cases))

    init {
        require(this.cases.isNotEmpty() && this.cases.size <= M6AlignmentLimits.MAX_TRUTH_CASES) {
            "M6 alignment truth corpus must be non-empty and bounded"
        }
    }
}

data class AlignmentTruthCase(
    val expectedDxPixels: Int,
    val expectedDyPixels: Int,
    val evidence: AlignedFrameEvidence,
) {
    init {
        require(expectedDxPixels % 2 == 0 && expectedDyPixels % 2 == 0) {
            "M6 synthetic reference truth must preserve CFA phase"
        }
        require(evidence.decision != FrameAlignmentDecision.REFERENCE) {
            "Alignment truth case must evaluate a non-reference frame"
        }
    }

    val translationErrorPixels: Double = hypot(
        (evidence.translation.dxPixels - expectedDxPixels).toDouble(),
        (evidence.translation.dyPixels - expectedDyPixels).toDouble(),
    )
}

data class AlignmentTruthThresholds(
    val maxP95TranslationErrorPixels: Double,
    val maxP99TranslationErrorPixels: Double,
    val catastrophicErrorPixels: Double,
    val maxCatastrophicFraction: Double,
    val minAcceptedFraction: Double,
) {
    init {
        require(maxP95TranslationErrorPixels.isFinite() && maxP95TranslationErrorPixels >= 0.0)
        require(maxP99TranslationErrorPixels.isFinite() && maxP99TranslationErrorPixels >= maxP95TranslationErrorPixels)
        require(catastrophicErrorPixels.isFinite() && catastrophicErrorPixels >= 0.0)
        require(maxCatastrophicFraction.isFinite() && maxCatastrophicFraction in 0.0..1.0)
        require(minAcceptedFraction.isFinite() && minAcceptedFraction in 0.0..1.0)
    }
}

data class AlignmentTruthReport(
    val caseCount: Int,
    val acceptedCount: Int,
    val acceptedFraction: Double,
    val p50TranslationErrorPixels: Double?,
    val p95TranslationErrorPixels: Double?,
    val p99TranslationErrorPixels: Double?,
    val catastrophicCount: Int,
    val catastrophicFraction: Double,
    val accepted: Boolean,
)

object AlignmentTruthEvaluator {
    fun evaluate(
        corpus: AlignmentTruthCorpus,
        thresholds: AlignmentTruthThresholds,
    ): AlignmentTruthReport {
        val acceptedCases = corpus.cases.filter { it.evidence.decision == FrameAlignmentDecision.ACCEPTED }
        val errors = acceptedCases.map { it.translationErrorPixels }.sorted()
        val catastrophic = corpus.cases.count { truth ->
            truth.evidence.decision != FrameAlignmentDecision.ACCEPTED ||
                truth.translationErrorPixels > thresholds.catastrophicErrorPixels
        }
        val acceptedFraction = acceptedCases.size.toDouble() / corpus.cases.size.toDouble()
        val catastrophicFraction = catastrophic.toDouble() / corpus.cases.size.toDouble()
        val p50 = percentile(errors, 0.50)
        val p95 = percentile(errors, 0.95)
        val p99 = percentile(errors, 0.99)
        val passes = acceptedFraction >= thresholds.minAcceptedFraction &&
            catastrophicFraction <= thresholds.maxCatastrophicFraction &&
            p95 != null && p95 <= thresholds.maxP95TranslationErrorPixels &&
            p99 != null && p99 <= thresholds.maxP99TranslationErrorPixels
        return AlignmentTruthReport(
            caseCount = corpus.cases.size,
            acceptedCount = acceptedCases.size,
            acceptedFraction = acceptedFraction,
            p50TranslationErrorPixels = p50,
            p95TranslationErrorPixels = p95,
            p99TranslationErrorPixels = p99,
            catastrophicCount = catastrophic,
            catastrophicFraction = catastrophicFraction,
            accepted = passes,
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double? {
        if (sorted.isEmpty()) return null
        val rank = kotlin.math.ceil(fraction * sorted.size.toDouble()).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
