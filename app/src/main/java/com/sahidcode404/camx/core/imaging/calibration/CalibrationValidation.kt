package com.sahidcode404.camx.core.imaging.calibration

import java.util.Collections
import kotlin.math.max
import kotlin.math.sqrt

private fun requireSiteIndex(siteIndex: Int) {
    require(siteIndex in 0..3) { "Calibration corpus CFA site index must be in 0..3" }
}

data class DarkResidualSample(
    val siteIndex: Int,
    val observedRawDn: Double,
) {
    init {
        requireSiteIndex(siteIndex)
        require(observedRawDn.isFinite() && observedRawDn >= 0.0) { "Dark sample must be finite and non-negative" }
    }
}

data class FlatResidualSample(
    val siteIndex: Int,
    val normalizedResponse: Double,
) {
    init {
        requireSiteIndex(siteIndex)
        require(normalizedResponse.isFinite() && normalizedResponse > 0.0) {
            "Flat response must be finite and positive"
        }
    }
}

data class LinearitySample(
    val siteIndex: Int,
    val relativeExposure: Double,
    val signalDn: Double,
) {
    init {
        requireSiteIndex(siteIndex)
        require(relativeExposure.isFinite() && relativeExposure > 0.0) {
            "Linearity exposure must be finite and positive"
        }
        require(signalDn.isFinite() && signalDn >= 0.0) { "Linearity signal must be finite and non-negative" }
    }
}

class CalibrationValidationCorpus(
    darkSamples: List<DarkResidualSample>,
    flatSamples: List<FlatResidualSample>,
    linearitySamples: List<LinearitySample>,
) {
    val darkSamples: List<DarkResidualSample> = Collections.unmodifiableList(ArrayList(darkSamples))
    val flatSamples: List<FlatResidualSample> = Collections.unmodifiableList(ArrayList(flatSamples))
    val linearitySamples: List<LinearitySample> = Collections.unmodifiableList(ArrayList(linearitySamples))

    init {
        val total = this.darkSamples.size.toLong() + this.flatSamples.size.toLong() + this.linearitySamples.size.toLong()
        require(total in 1..M5CalibrationLimits.MAX_VALIDATION_SAMPLES.toLong()) {
            "M5 validation corpus must be non-empty and bounded"
        }
        require(allSitesPresent(this.darkSamples.map { it.siteIndex })) { "Dark corpus must cover all CFA sites" }
        require(allSitesPresent(this.flatSamples.map { it.siteIndex })) { "Flat corpus must cover all CFA sites" }
        for (site in 0..3) {
            require(this.linearitySamples.count { it.siteIndex == site } >= 2) {
                "Linearity corpus requires at least two exposures for every CFA site"
            }
        }
    }
}

data class CalibrationValidationThresholds(
    val maxDarkRmseDn: Double,
    val maxFlatNormalizedRmse: Double,
    val maxLinearityNormalizedRmse: Double,
    val minBlackLevelConfidence: Double,
    val minWhiteLevelConfidence: Double,
    val minCfaAndActiveAreaConfidence: Double,
    val minShotNoiseConfidence: Double,
    val minReadNoiseConfidence: Double,
    val minFixedPatternNoiseConfidence: Double,
    val minColorCalibrationConfidenceWhenPresent: Double,
) {
    init {
        listOf(maxDarkRmseDn, maxFlatNormalizedRmse, maxLinearityNormalizedRmse).forEach {
            require(it.isFinite() && it >= 0.0) { "Residual thresholds must be finite and non-negative" }
        }
        listOf(
            minBlackLevelConfidence,
            minWhiteLevelConfidence,
            minCfaAndActiveAreaConfidence,
            minShotNoiseConfidence,
            minReadNoiseConfidence,
            minFixedPatternNoiseConfidence,
            minColorCalibrationConfidenceWhenPresent,
        ).forEach {
            require(it.isFinite() && it in 0.0..1.0) { "Confidence thresholds must be finite and in [0, 1]" }
        }
    }
}

data class CalibrationValidationReport(
    val darkRmseDn: Double,
    val flatNormalizedRmse: Double,
    val linearityNormalizedRmse: Double,
    val darkResidualPassed: Boolean,
    val flatResidualPassed: Boolean,
    val linearityResidualPassed: Boolean,
    val blackLevelConfidencePassed: Boolean,
    val whiteLevelConfidencePassed: Boolean,
    val cfaAndActiveAreaConfidencePassed: Boolean,
    val shotNoiseConfidencePassed: Boolean,
    val readNoiseConfidencePassed: Boolean,
    val fixedPatternNoiseConfidencePassed: Boolean,
    val colorCalibrationConfidencePassed: Boolean,
) {
    val accepted: Boolean =
        darkResidualPassed && flatResidualPassed && linearityResidualPassed &&
            blackLevelConfidencePassed && whiteLevelConfidencePassed && cfaAndActiveAreaConfidencePassed &&
            shotNoiseConfidencePassed && readNoiseConfidencePassed && fixedPatternNoiseConfidencePassed &&
            colorCalibrationConfidencePassed
}

object CalibrationCorpusEvaluator {
    fun evaluate(
        profile: M5CalibrationProfile,
        corpus: CalibrationValidationCorpus,
        thresholds: CalibrationValidationThresholds,
    ): CalibrationValidationReport {
        val darkRmse = sqrt(
            corpus.darkSamples.sumOf { sample ->
                val residual = sample.observedRawDn - profile.blackLevelsDn.valueByIndex(sample.siteIndex)
                residual * residual
            } / corpus.darkSamples.size.toDouble(),
        )
        val flatRmse = sqrt(
            corpus.flatSamples.sumOf { sample ->
                val residual = sample.normalizedResponse - 1.0
                residual * residual
            } / corpus.flatSamples.size.toDouble(),
        )
        var worstLinearity = 0.0
        for (site in 0..3) {
            val samples = corpus.linearitySamples.filter { it.siteIndex == site }
            var sumXy = 0.0
            var sumX2 = 0.0
            samples.forEach { sample ->
                sumXy += sample.relativeExposure * sample.signalDn
                sumX2 += sample.relativeExposure * sample.relativeExposure
            }
            require(sumX2 > 0.0) { "Linearity corpus has no exposure energy" }
            val slope = sumXy / sumX2
            var squaredError = 0.0
            var scale = 0.0
            samples.forEach { sample ->
                val expected = slope * sample.relativeExposure
                val residual = sample.signalDn - expected
                squaredError += residual * residual
                scale = max(scale, expected)
            }
            require(scale > 0.0) { "Linearity corpus has no positive calibrated response" }
            val normalizedRmse = sqrt(squaredError / samples.size.toDouble()) / scale
            worstLinearity = max(worstLinearity, normalizedRmse)
        }

        val confidence = profile.confidence
        return CalibrationValidationReport(
            darkRmseDn = darkRmse,
            flatNormalizedRmse = flatRmse,
            linearityNormalizedRmse = worstLinearity,
            darkResidualPassed = darkRmse <= thresholds.maxDarkRmseDn,
            flatResidualPassed = flatRmse <= thresholds.maxFlatNormalizedRmse,
            linearityResidualPassed = worstLinearity <= thresholds.maxLinearityNormalizedRmse,
            blackLevelConfidencePassed = confidence.blackLevel >= thresholds.minBlackLevelConfidence,
            whiteLevelConfidencePassed = confidence.whiteLevel >= thresholds.minWhiteLevelConfidence,
            cfaAndActiveAreaConfidencePassed = confidence.cfaAndActiveArea >= thresholds.minCfaAndActiveAreaConfidence,
            shotNoiseConfidencePassed = confidence.shotNoise >= thresholds.minShotNoiseConfidence,
            readNoiseConfidencePassed = confidence.readNoise >= thresholds.minReadNoiseConfidence,
            fixedPatternNoiseConfidencePassed =
                confidence.fixedPatternNoise >= thresholds.minFixedPatternNoiseConfidence,
            colorCalibrationConfidencePassed = profile.colorCalibration == null ||
                checkNotNull(confidence.colorCalibration) >= thresholds.minColorCalibrationConfidenceWhenPresent,
        )
    }
}

private fun allSitesPresent(indices: List<Int>): Boolean = (0..3).all(indices::contains)
