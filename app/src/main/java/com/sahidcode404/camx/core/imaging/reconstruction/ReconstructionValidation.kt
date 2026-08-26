package com.sahidcode404.camx.core.imaging.reconstruction

import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrame
import kotlin.math.abs
import kotlin.math.sqrt

class ReconstructionTruthRaster(
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    expectedRadianceDn: DoubleArray,
) {
    private val expectedRadianceDn = expectedRadianceDn.copyOf()

    init {
        require(width > 0 && height > 0)
        val count = width.toLong() * height.toLong()
        require(count in 1..M7ReconstructionLimits.MAX_VALIDATION_SAMPLES.toLong()) {
            "M7 truth raster must be non-empty and bounded"
        }
        require(this.expectedRadianceDn.size.toLong() == count)
        require(this.expectedRadianceDn.all { it.isFinite() && it >= 0.0 })
    }

    fun expectedAt(sensorX: Int, sensorY: Int): Double {
        require(sensorX in originX until originX + width && sensorY in originY until originY + height)
        return expectedRadianceDn[(sensorY - originY) * width + (sensorX - originX)]
    }
}

data class ReconstructionQualityMetrics(
    val evaluatedSamples: Int,
    val referenceRmseDn: Double,
    val reconstructedRmseDn: Double,
    val rmseGainFraction: Double,
    val referenceMeanVarianceDn2: Double,
    val reconstructedMeanVarianceDn2: Double,
    val varianceGainFraction: Double,
    val artifactRegressionFraction: Double,
) {
    init {
        require(evaluatedSamples > 0)
        listOf(
            referenceRmseDn,
            reconstructedRmseDn,
            rmseGainFraction,
            referenceMeanVarianceDn2,
            reconstructedMeanVarianceDn2,
            varianceGainFraction,
            artifactRegressionFraction,
        ).forEach { require(it.isFinite()) }
        require(artifactRegressionFraction in 0.0..1.0)
    }
}

object ReconstructionTruthEvaluator {
    fun evaluate(
        product: FusedCfaRadiance,
        reference: CalibratedMeasurementFrame,
        truth: ReconstructionTruthRaster,
        regressionToleranceDn: Double = 1e-6,
    ): ReconstructionQualityMetrics {
        require(regressionToleranceDn.isFinite() && regressionToleranceDn >= 0.0)
        require(product.activeArea.left == truth.originX && product.activeArea.top == truth.originY &&
            product.width == truth.width && product.height == truth.height
        ) { "M7 truth raster must match the FusedCfaRadiance active reference grid" }
        var count = 0
        var referenceSquared = 0.0
        var reconstructedSquared = 0.0
        var referenceVariance = 0.0
        var reconstructedVariance = 0.0
        var regressions = 0
        for (y in truth.originY until truth.originY + truth.height) {
            for (x in truth.originX until truth.originX + truth.width) {
                val fused = product.sampleAt(x, y)
                if (!fused.measurementValid) continue
                val ref = reference.sampleAt(x, y)
                if (ref.lowCensored || ref.highCensored) continue
                val expected = truth.expectedAt(x, y)
                val refError = abs(ref.signalDn - expected)
                val fusedError = abs(fused.radianceDn - expected)
                referenceSquared += refError * refError
                reconstructedSquared += fusedError * fusedError
                referenceVariance += ref.varianceDn2
                reconstructedVariance += fused.varianceDn2
                if (fusedError > refError + regressionToleranceDn) regressions++
                count++
            }
        }
        require(count > 0) { "M7 truth evaluation requires at least one uncensored valid measurement" }
        val refRmse = sqrt(referenceSquared / count.toDouble())
        val fusedRmse = sqrt(reconstructedSquared / count.toDouble())
        val refVar = referenceVariance / count.toDouble()
        val fusedVar = reconstructedVariance / count.toDouble()
        return ReconstructionQualityMetrics(
            evaluatedSamples = count,
            referenceRmseDn = refRmse,
            reconstructedRmseDn = fusedRmse,
            rmseGainFraction = if (refRmse == 0.0) 0.0 else (refRmse - fusedRmse) / refRmse,
            referenceMeanVarianceDn2 = refVar,
            reconstructedMeanVarianceDn2 = fusedVar,
            varianceGainFraction = if (refVar == 0.0) 0.0 else (refVar - fusedVar) / refVar,
            artifactRegressionFraction = regressions.toDouble() / count.toDouble(),
        )
    }
}
