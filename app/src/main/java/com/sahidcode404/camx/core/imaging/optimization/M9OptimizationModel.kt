package com.sahidcode404.camx.core.imaging.optimization

object M9OptimizationLimits {
    const val MAX_FUSION_FRAMES = 32
    const val MAX_FUSION_PIXELS = 16 * 1024 * 1024
    const val MAX_FUSION_SAMPLES = 64 * 1024 * 1024
}

enum class M9FusionBackend(val nativeCode: Int) {
    PORTABLE_SCALAR(0),
    ARM_NEON(1),
    X86_SSE2(2),
    ;

    companion object {
        fun fromNativeCode(code: Int): M9FusionBackend? = entries.firstOrNull { it.nativeCode == code }
    }
}

data class M9DenseFusionShape private constructor(
    val frameCount: Int,
    val pixelCount: Int,
    val sampleCount: Int,
) {
    companion object {
        fun of(frameCount: Int, pixelCount: Int): M9DenseFusionShape {
            require(frameCount in 1..M9OptimizationLimits.MAX_FUSION_FRAMES) {
                "M9 fusion frame count must be positive and bounded"
            }
            require(pixelCount in 1..M9OptimizationLimits.MAX_FUSION_PIXELS) {
                "M9 fusion pixel count must be positive and bounded"
            }
            val sampleCount = try {
                Math.multiplyExact(frameCount, pixelCount)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("M9 fusion sample-count overflow", error)
            }
            require(sampleCount <= M9OptimizationLimits.MAX_FUSION_SAMPLES) {
                "M9 fusion sample count exceeds the bounded candidate workspace"
            }
            return M9DenseFusionShape(frameCount, pixelCount, sampleCount)
        }
    }
}

class M9DenseFusionResult internal constructor(
    val backend: M9FusionBackend,
    radianceDn: FloatArray,
    varianceDn2: FloatArray,
    effectiveSampleCount: FloatArray,
) {
    private val radiance = radianceDn.copyOf()
    private val variance = varianceDn2.copyOf()
    private val effective = effectiveSampleCount.copyOf()

    val pixelCount: Int
        get() = radiance.size

    init {
        require(variance.size == radiance.size && effective.size == radiance.size) {
            "M9 fusion result arrays must have identical extents"
        }
        require(radiance.isNotEmpty()) { "M9 fusion result cannot be empty" }
        require(radiance.all { it.isFinite() && it >= 0f })
        require(variance.all { it.isFinite() && it > 0f })
        require(effective.all { it.isFinite() && it > 0f })
    }

    fun radianceAt(index: Int): Float = radiance[index]
    fun varianceAt(index: Int): Float = variance[index]
    fun effectiveSampleCountAt(index: Int): Float = effective[index]
}
