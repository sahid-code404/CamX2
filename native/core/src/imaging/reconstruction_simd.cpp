#include "camx/reconstruction_simd.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#if defined(__SSE2__)
#include <emmintrin.h>
#include <xmmintrin.h>
#endif

namespace camx::imaging {
namespace {

bool ValidShape(std::size_t frame_count, std::size_t pixel_count) noexcept {
  if (frame_count == 0U || frame_count > kM9MaxFusionFrames) return false;
  if (pixel_count == 0U || pixel_count > kM9MaxFusionPixels) return false;
  return pixel_count <= kM9MaxFusionSamples / frame_count;
}

void FuseScalarPixel(
    const float* signals,
    const float* variances,
    std::size_t frame_count,
    std::size_t pixel_count,
    std::size_t pixel,
    float minimum_variance,
    float& radiance,
    float& variance,
    float& effective) noexcept {
  float sum_weight = 0.0F;
  float sum_weighted_signal = 0.0F;
  float sum_squared_weight = 0.0F;
  for (std::size_t frame = 0U; frame < frame_count; ++frame) {
    const std::size_t index = frame * pixel_count + pixel;
    const float sample_variance = std::max(variances[index], minimum_variance);
    const float weight = 1.0F / sample_variance;
    sum_weight += weight;
    sum_weighted_signal += weight * signals[index];
    sum_squared_weight += weight * weight;
  }
  radiance = sum_weighted_signal / sum_weight;
  variance = 1.0F / sum_weight;
  effective = (sum_weight * sum_weight) / sum_squared_weight;
}

#if defined(__aarch64__) || defined(__ARM_NEON)
float32x4_t ReciprocalNeon(float32x4_t value) noexcept {
#if defined(__aarch64__)
  return vdivq_f32(vdupq_n_f32(1.0F), value);
#else
  float32x4_t reciprocal = vrecpeq_f32(value);
  reciprocal = vmulq_f32(vrecpsq_f32(value, reciprocal), reciprocal);
  reciprocal = vmulq_f32(vrecpsq_f32(value, reciprocal), reciprocal);
  return reciprocal;
#endif
}

void FuseNeon(
    const float* signals,
    const float* variances,
    std::size_t frame_count,
    std::size_t pixel_count,
    float minimum_variance,
    float* fused_radiance,
    float* fused_variance,
    float* effective_sample_count) noexcept {
  const float32x4_t minimum = vdupq_n_f32(minimum_variance);
  std::size_t pixel = 0U;
  for (; pixel + 4U <= pixel_count; pixel += 4U) {
    float32x4_t sum_weight = vdupq_n_f32(0.0F);
    float32x4_t sum_weighted_signal = vdupq_n_f32(0.0F);
    float32x4_t sum_squared_weight = vdupq_n_f32(0.0F);
    for (std::size_t frame = 0U; frame < frame_count; ++frame) {
      const std::size_t index = frame * pixel_count + pixel;
      const float32x4_t signal = vld1q_f32(signals + index);
      const float32x4_t variance = vmaxq_f32(vld1q_f32(variances + index), minimum);
      const float32x4_t weight = ReciprocalNeon(variance);
      sum_weight = vaddq_f32(sum_weight, weight);
      sum_weighted_signal = vaddq_f32(sum_weighted_signal, vmulq_f32(weight, signal));
      sum_squared_weight = vaddq_f32(sum_squared_weight, vmulq_f32(weight, weight));
    }
    const float32x4_t inverse_sum_weight = ReciprocalNeon(sum_weight);
    const float32x4_t radiance = vmulq_f32(sum_weighted_signal, inverse_sum_weight);
    const float32x4_t effective = vmulq_f32(
        vmulq_f32(sum_weight, sum_weight),
        ReciprocalNeon(sum_squared_weight));
    vst1q_f32(fused_radiance + pixel, radiance);
    vst1q_f32(fused_variance + pixel, inverse_sum_weight);
    vst1q_f32(effective_sample_count + pixel, effective);
  }
  for (; pixel < pixel_count; ++pixel) {
    FuseScalarPixel(
        signals,
        variances,
        frame_count,
        pixel_count,
        pixel,
        minimum_variance,
        fused_radiance[pixel],
        fused_variance[pixel],
        effective_sample_count[pixel]);
  }
}
#endif

#if defined(__SSE2__)
void FuseSse2(
    const float* signals,
    const float* variances,
    std::size_t frame_count,
    std::size_t pixel_count,
    float minimum_variance,
    float* fused_radiance,
    float* fused_variance,
    float* effective_sample_count) noexcept {
  const __m128 one = _mm_set1_ps(1.0F);
  const __m128 minimum = _mm_set1_ps(minimum_variance);
  std::size_t pixel = 0U;
  for (; pixel + 4U <= pixel_count; pixel += 4U) {
    __m128 sum_weight = _mm_setzero_ps();
    __m128 sum_weighted_signal = _mm_setzero_ps();
    __m128 sum_squared_weight = _mm_setzero_ps();
    for (std::size_t frame = 0U; frame < frame_count; ++frame) {
      const std::size_t index = frame * pixel_count + pixel;
      const __m128 signal = _mm_loadu_ps(signals + index);
      const __m128 variance = _mm_max_ps(_mm_loadu_ps(variances + index), minimum);
      const __m128 weight = _mm_div_ps(one, variance);
      sum_weight = _mm_add_ps(sum_weight, weight);
      sum_weighted_signal = _mm_add_ps(sum_weighted_signal, _mm_mul_ps(weight, signal));
      sum_squared_weight = _mm_add_ps(sum_squared_weight, _mm_mul_ps(weight, weight));
    }
    const __m128 inverse_sum_weight = _mm_div_ps(one, sum_weight);
    const __m128 radiance = _mm_mul_ps(sum_weighted_signal, inverse_sum_weight);
    const __m128 effective = _mm_div_ps(_mm_mul_ps(sum_weight, sum_weight), sum_squared_weight);
    _mm_storeu_ps(fused_radiance + pixel, radiance);
    _mm_storeu_ps(fused_variance + pixel, inverse_sum_weight);
    _mm_storeu_ps(effective_sample_count + pixel, effective);
  }
  for (; pixel < pixel_count; ++pixel) {
    FuseScalarPixel(
        signals,
        variances,
        frame_count,
        pixel_count,
        pixel,
        minimum_variance,
        fused_radiance[pixel],
        fused_variance[pixel],
        effective_sample_count[pixel]);
  }
}
#endif

}  // namespace

FusionBackend CompiledFusionBackend() noexcept {
#if defined(__aarch64__) || defined(__ARM_NEON)
  return FusionBackend::kArmNeon;
#elif defined(__SSE2__)
  return FusionBackend::kX86Sse2;
#else
  return FusionBackend::kPortableScalar;
#endif
}

FusionStatus FuseDenseInverseVariance(
    const float* signals,
    const float* variances,
    std::size_t frame_count,
    std::size_t pixel_count,
    float minimum_variance,
    float* fused_radiance,
    float* fused_variance,
    float* effective_sample_count) noexcept {
  if (signals == nullptr || variances == nullptr || fused_radiance == nullptr ||
      fused_variance == nullptr || effective_sample_count == nullptr) {
    return {};
  }
  if (!ValidShape(frame_count, pixel_count) || !std::isfinite(minimum_variance) ||
      minimum_variance <= 0.0F) {
    return {};
  }

#if defined(__aarch64__) || defined(__ARM_NEON)
  FuseNeon(
      signals,
      variances,
      frame_count,
      pixel_count,
      minimum_variance,
      fused_radiance,
      fused_variance,
      effective_sample_count);
#elif defined(__SSE2__)
  FuseSse2(
      signals,
      variances,
      frame_count,
      pixel_count,
      minimum_variance,
      fused_radiance,
      fused_variance,
      effective_sample_count);
#else
  for (std::size_t pixel = 0U; pixel < pixel_count; ++pixel) {
    FuseScalarPixel(
        signals,
        variances,
        frame_count,
        pixel_count,
        pixel,
        minimum_variance,
        fused_radiance[pixel],
        fused_variance[pixel],
        effective_sample_count[pixel]);
  }
#endif

  for (std::size_t pixel = 0U; pixel < pixel_count; ++pixel) {
    if (!std::isfinite(fused_radiance[pixel]) || fused_radiance[pixel] < 0.0F ||
        !std::isfinite(fused_variance[pixel]) || fused_variance[pixel] <= 0.0F ||
        !std::isfinite(effective_sample_count[pixel]) || effective_sample_count[pixel] <= 0.0F) {
      return {};
    }
  }
  return FusionStatus{true, CompiledFusionBackend()};
}

}  // namespace camx::imaging
