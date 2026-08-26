package com.sahidcode404.camx.core.imaging.reconstruction

import com.sahidcode404.camx.core.imaging.calibration.CfaSiteColor
import com.sahidcode404.camx.core.imaging.graph.GraphRepresentation
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceReconstructionEngineTest {
    @Test
    fun symmetricNoiseImprovesAgainstSameSingleRaw() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(10, -10))
        val product = M7ReconstructionTestFixtures.reconstruct(measurements)
        val metrics = ReconstructionTruthEvaluator.evaluate(
            product,
            measurements.frames[0],
            M7ReconstructionTestFixtures.truthRaster(),
        )

        assertEquals(10.0, metrics.referenceRmseDn, 1e-9)
        assertEquals(0.0, metrics.reconstructedRmseDn, 1e-6)
        assertTrue(metrics.rmseGainFraction > 0.99)
        assertEquals(0.5, metrics.reconstructedMeanVarianceDn2 / metrics.referenceMeanVarianceDn2, 1e-6)
        assertEquals(0.0, metrics.artifactRegressionFraction, 0.0)
    }

    @Test
    fun occludedMeasurementCannotGhostReferenceGrid() {
        val truth = M7ReconstructionTestFixtures.truthValues()
        val targetX = 2
        val targetY = 2
        val targetIndex = targetY * M7ReconstructionTestFixtures.size.width + targetX
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            offsets = listOf(0, 0),
            mutations = mapOf((1 to targetIndex) to 3500),
        )
        val product = M7ReconstructionTestFixtures.reconstruct(measurements)
        val sample = product.sampleAt(targetX, targetY)

        assertEquals(truth[targetIndex].toDouble(), sample.radianceDn, 1e-6)
        assertEquals(1, sample.contributingFrames)
        assertTrue(sample.referenceOnly)
        assertTrue(product.uncertainty.rejectedOccludedMeasurements > 0L)
    }

    @Test
    fun exposureMismatchNarrowsToReferenceOnly() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(
            offsets = listOf(0, 0),
            exposureTimesNs = listOf(5_000_000L, 10_000_000L),
        )
        val product = M7ReconstructionTestFixtures.reconstruct(measurements)

        assertEquals(ReconstructionFallbackKind.REFERENCE_ONLY, product.fallbackKind)
        assertEquals(listOf(0), product.provenance.includedOrdinals)
        assertEquals(
            ReconstructionFrameDecision.EXCLUDED_EXPOSURE_IDENTITY,
            product.frameEvidence[1].decision,
        )
    }

    @Test
    fun censoredReferenceBoundaryIsPreservedNotInvented() {
        val truth = M7ReconstructionTestFixtures.truthValues()
        val targetIndex = 3 * M7ReconstructionTestFixtures.size.width + 3
        val first = truth.copyOf().also { it[targetIndex] = 4095 }
        val second = truth.copyOf().also { it[targetIndex] = 4095 }
        val measurements = M7ReconstructionTestFixtures.measurementsFromRasters(listOf(first, second))
        val product = M7ReconstructionTestFixtures.reconstruct(measurements)
        val sample = product.sampleAt(3, 3)

        assertTrue(sample.highCensored)
        assertFalse(sample.measurementValid)
        assertEquals(0, sample.contributingFrames)
        assertEquals(4095.0, sample.radianceDn, 1e-6)
    }

    @Test
    fun foreignAlignmentEvidenceIsRejectedByExactMeasurementBinding() {
        val measurementsA = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(0, 0))
        val measurementsB = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(20, -20))
        val alignmentA = M7ReconstructionTestFixtures.align(measurementsA)
        val request = M7ReconstructionTestFixtures.request()

        val error = runCatching {
            ReconstructionReservation.forInputs(
                measurementsB,
                alignmentA,
                request,
                8L * 1024L * 1024L,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun deterministicOutputAndManifestDigestsRepeatExactly() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(10, -10))
        val alignment = M7ReconstructionTestFixtures.align(measurements)
        val first = M7ReconstructionTestFixtures.reconstruct(measurements, alignment)
        val second = M7ReconstructionTestFixtures.reconstruct(measurements, alignment)

        assertEquals(first.provenance.outputSha256, second.provenance.outputSha256)
        assertEquals(first.provenance.manifestSha256, second.provenance.manifestSha256)
        assertEquals(first.provenance.graphSha256, second.provenance.graphSha256)
        assertFalse(first.provenance.learnedPriorChangedPixels)
    }

    @Test
    fun reservationFailsClosedBelowProvenResidentBound() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(0, 0))
        val alignment = M7ReconstructionTestFixtures.align(measurements)
        val request = M7ReconstructionTestFixtures.request()

        val error = runCatching {
            ReconstructionReservation.forInputs(measurements, alignment, request, 1L)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun productRemainsOneTimesActualCfaGridWithoutDemosaic() {
        val measurements = M7ReconstructionTestFixtures.measurementsFromOffsets(listOf(0, 0))
        val product = M7ReconstructionTestFixtures.reconstruct(measurements)

        assertEquals(GraphRepresentation.FUSED_CFA_RADIANCE, product.representation)
        assertEquals(1, product.scaleNumerator)
        assertEquals(1, product.scaleDenominator)
        assertEquals(M7ReconstructionTestFixtures.size.width, product.width)
        assertEquals(M7ReconstructionTestFixtures.size.height, product.height)
        assertEquals(CfaSiteColor.RED, product.sampleAt(0, 0).cfaColor)
        assertEquals(CfaSiteColor.GREEN, product.sampleAt(1, 0).cfaColor)
        assertEquals(CfaSiteColor.BLUE, product.sampleAt(1, 1).cfaColor)
        assertTrue(abs(product.sampleAt(4, 4).radianceDn - M7ReconstructionTestFixtures.truthValues()[36]) < 1e-6)
    }
}
