package com.sahidcode404.camx.core.imaging.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationValidationTest {
    @Test
    fun syntheticDarkFlatLinearityCorpusPasses() {
        val report = CalibrationCorpusEvaluator.evaluate(
            M5CalibrationTestFixtures.profile(),
            M5CalibrationTestFixtures.validationCorpus(),
            M5CalibrationTestFixtures.thresholds(),
        )
        assertTrue(report.darkResidualPassed)
        assertTrue(report.flatResidualPassed)
        assertTrue(report.linearityResidualPassed)
        assertTrue(report.accepted)
    }

    @Test
    fun badDarkResidualFails() {
        val base = M5CalibrationTestFixtures.validationCorpus()
        val dark = base.darkSamples.map { DarkResidualSample(it.siteIndex, 100.0) }
        val corpus = CalibrationValidationCorpus(dark, base.flatSamples, base.linearitySamples)
        val report = CalibrationCorpusEvaluator.evaluate(
            M5CalibrationTestFixtures.profile(),
            corpus,
            M5CalibrationTestFixtures.thresholds(),
        )
        assertFalse(report.darkResidualPassed)
        assertFalse(report.accepted)
    }

    @Test
    fun insufficientNoiseConfidenceFails() {
        val profile = M5CalibrationTestFixtures.profile(
            confidence = M5CalibrationTestFixtures.confidence(shotNoise = 0.3),
        )
        val report = CalibrationCorpusEvaluator.evaluate(
            profile,
            M5CalibrationTestFixtures.validationCorpus(),
            M5CalibrationTestFixtures.thresholds(),
        )
        assertFalse(report.shotNoiseConfidencePassed)
        assertTrue(report.readNoiseConfidencePassed)
        assertFalse(report.accepted)
    }

    @Test
    fun validationCorpusIsDefensivelyFrozen() {
        val dark = mutableListOf<DarkResidualSample>()
        val flat = mutableListOf<FlatResidualSample>()
        val linearity = mutableListOf<LinearitySample>()
        for (site in 0..3) {
            dark += DarkResidualSample(site, 64.0)
            flat += FlatResidualSample(site, 1.0)
            linearity += LinearitySample(site, 1.0, 100.0)
            linearity += LinearitySample(site, 2.0, 200.0)
        }
        val corpus = CalibrationValidationCorpus(dark, flat, linearity)
        dark += DarkResidualSample(0, 999.0)
        flat.clear()
        linearity.clear()
        assertEquals(4, corpus.darkSamples.size)
        assertEquals(4, corpus.flatSamples.size)
        assertEquals(8, corpus.linearitySamples.size)
    }
}
