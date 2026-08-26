package com.sahidcode404.camx.core.imaging.optimization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M9OptimizationContractTest {
    @Test
    fun shapeAcceptsExactSampleBoundaryAndRejectsOverflow() {
        val exact = M9DenseFusionShape.of(
            M9OptimizationLimits.MAX_FUSION_FRAMES,
            M9OptimizationLimits.MAX_FUSION_SAMPLES / M9OptimizationLimits.MAX_FUSION_FRAMES,
        )
        assertEquals(M9OptimizationLimits.MAX_FUSION_SAMPLES, exact.sampleCount)

        val error = runCatching {
            M9DenseFusionShape.of(
                M9OptimizationLimits.MAX_FUSION_FRAMES,
                M9OptimizationLimits.MAX_FUSION_SAMPLES / M9OptimizationLimits.MAX_FUSION_FRAMES + 1,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun malformedDenseInputsFailBeforeAnyNativeDispatch() {
        val error = runCatching {
            NativeSimdFusionCandidate.fuseDense(
                signalsByFrame = floatArrayOf(10f),
                variancesByFrame = floatArrayOf(1f, 1f),
                frameCount = 2,
                pixelCount = 1,
                minimumVarianceDn2 = 1e-6f,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun backendCodesRemainStableAcrossTheJniContract() {
        assertEquals(M9FusionBackend.PORTABLE_SCALAR, M9FusionBackend.fromNativeCode(0))
        assertEquals(M9FusionBackend.ARM_NEON, M9FusionBackend.fromNativeCode(1))
        assertEquals(M9FusionBackend.X86_SSE2, M9FusionBackend.fromNativeCode(2))
        assertEquals(null, M9FusionBackend.fromNativeCode(99))
    }
}
