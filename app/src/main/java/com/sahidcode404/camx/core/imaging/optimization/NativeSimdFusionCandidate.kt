package com.sahidcode404.camx.core.imaging.optimization

/**
 * Provisional M9 arithmetic provider. It is intentionally not routed into M7 production output until
 * exact-device differential and performance/energy/thermal gates prove that it is worth shipping.
 */
object NativeSimdFusionCandidate {
    private val loaded = runCatching { System.loadLibrary("camx_core") }.isSuccess

    val libraryAvailable: Boolean
        get() = loaded

    fun fuseDense(
        signalsByFrame: FloatArray,
        variancesByFrame: FloatArray,
        frameCount: Int,
        pixelCount: Int,
        minimumVarianceDn2: Float,
    ): M9DenseFusionResult? {
        val shape = M9DenseFusionShape.of(frameCount, pixelCount)
        require(signalsByFrame.size == shape.sampleCount) {
            "M9 signal raster extent does not match frameCount × pixelCount"
        }
        require(variancesByFrame.size == shape.sampleCount) {
            "M9 variance raster extent does not match frameCount × pixelCount"
        }
        require(signalsByFrame.all { it.isFinite() && it >= 0f }) {
            "M9 signal samples must be finite and non-negative"
        }
        require(variancesByFrame.all { it.isFinite() && it >= 0f }) {
            "M9 variance samples must be finite and non-negative"
        }
        require(minimumVarianceDn2.isFinite() && minimumVarianceDn2 > 0f) {
            "M9 minimum variance must be finite and positive"
        }
        if (!loaded) return null

        val radiance = FloatArray(pixelCount)
        val variance = FloatArray(pixelCount)
        val effective = FloatArray(pixelCount)
        val backendCode = runCatching {
            nativeFuseDense(
                signalsByFrame,
                variancesByFrame,
                frameCount,
                pixelCount,
                minimumVarianceDn2,
                radiance,
                variance,
                effective,
            )
        }.getOrNull() ?: return null
        val backend = M9FusionBackend.fromNativeCode(backendCode) ?: return null
        return runCatching {
            M9DenseFusionResult(backend, radiance, variance, effective)
        }.getOrNull()
    }

    private external fun nativeFuseDense(
        signalsByFrame: FloatArray,
        variancesByFrame: FloatArray,
        frameCount: Int,
        pixelCount: Int,
        minimumVarianceDn2: Float,
        fusedRadianceDn: FloatArray,
        fusedVarianceDn2: FloatArray,
        effectiveSampleCount: FloatArray,
    ): Int
}
