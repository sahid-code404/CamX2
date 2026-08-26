#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "camx/reconstruction_simd.hpp"

namespace {

bool NearlyEqual(float actual, double expected, double relative_tolerance = 2.0e-5) {
  const double scale = std::max(1.0, std::abs(expected));
  return std::abs(static_cast<double>(actual) - expected) <= relative_tolerance * scale;
}

void TestDifferentialAgainstDoubleOracle() {
  constexpr std::size_t frame_count = 3U;
  constexpr std::size_t pixel_count = 17U;
  constexpr float minimum_variance = 1.0e-6F;
  std::vector<float> signals(frame_count * pixel_count);
  std::vector<float> variances(frame_count * pixel_count);
  for (std::size_t frame = 0U; frame < frame_count; ++frame) {
    for (std::size_t pixel = 0U; pixel < pixel_count; ++pixel) {
      const std::size_t index = frame * pixel_count + pixel;
      signals[index] = 256.0F + static_cast<float>(pixel * 7U + frame * 3U);
      variances[index] = 2.0F + static_cast<float>((pixel + frame * 5U) % 11U) * 0.5F;
    }
  }

  std::vector<float> radiance(pixel_count);
  std::vector<float> variance(pixel_count);
  std::vector<float> effective(pixel_count);
  const auto status = camx::imaging::FuseDenseInverseVariance(
      signals.data(),
      variances.data(),
      frame_count,
      pixel_count,
      minimum_variance,
      radiance.data(),
      variance.data(),
      effective.data());
  assert(status.ok);

#if defined(__aarch64__) || defined(__ARM_NEON)
  assert(status.backend == camx::imaging::FusionBackend::kArmNeon);
#elif defined(__SSE2__)
  assert(status.backend == camx::imaging::FusionBackend::kX86Sse2);
#else
  assert(status.backend == camx::imaging::FusionBackend::kPortableScalar);
#endif

  for (std::size_t pixel = 0U; pixel < pixel_count; ++pixel) {
    double sum_weight = 0.0;
    double sum_weighted_signal = 0.0;
    double sum_squared_weight = 0.0;
    for (std::size_t frame = 0U; frame < frame_count; ++frame) {
      const std::size_t index = frame * pixel_count + pixel;
      const double sample_variance = std::max(
          static_cast<double>(variances[index]),
          static_cast<double>(minimum_variance));
      const double weight = 1.0 / sample_variance;
      sum_weight += weight;
      sum_weighted_signal += weight * static_cast<double>(signals[index]);
      sum_squared_weight += weight * weight;
    }
    assert(NearlyEqual(radiance[pixel], sum_weighted_signal / sum_weight));
    assert(NearlyEqual(variance[pixel], 1.0 / sum_weight));
    assert(NearlyEqual(effective[pixel], sum_weight * sum_weight / sum_squared_weight));
  }
}

void TestSingleFrameAndVarianceClamp() {
  const std::vector<float> signals{12.0F, 25.0F, 37.0F, 49.0F, 61.0F};
  const std::vector<float> variances{0.0F, 1.0F, 2.0F, 3.0F, 4.0F};
  std::vector<float> radiance(signals.size());
  std::vector<float> variance(signals.size());
  std::vector<float> effective(signals.size());
  const auto status = camx::imaging::FuseDenseInverseVariance(
      signals.data(),
      variances.data(),
      1U,
      signals.size(),
      0.5F,
      radiance.data(),
      variance.data(),
      effective.data());
  assert(status.ok);
  for (std::size_t index = 0U; index < signals.size(); ++index) {
    assert(NearlyEqual(radiance[index], signals[index], 1.0e-6));
    assert(NearlyEqual(variance[index], std::max(variances[index], 0.5F), 1.0e-6));
    assert(NearlyEqual(effective[index], 1.0, 1.0e-6));
  }
}

void TestMalformedInputFailsClosed() {
  float output = 0.0F;
  const float input = 1.0F;
  assert(!camx::imaging::FuseDenseInverseVariance(
      nullptr, &input, 1U, 1U, 1.0F, &output, &output, &output).ok);
  assert(!camx::imaging::FuseDenseInverseVariance(
      &input, &input, 0U, 1U, 1.0F, &output, &output, &output).ok);
  assert(!camx::imaging::FuseDenseInverseVariance(
      &input, &input, 1U, 1U, 0.0F, &output, &output, &output).ok);
  assert(!camx::imaging::FuseDenseInverseVariance(
      &input,
      &input,
      camx::imaging::kM9MaxFusionFrames,
      camx::imaging::kM9MaxFusionSamples / camx::imaging::kM9MaxFusionFrames + 1U,
      1.0F,
      &output,
      &output,
      &output).ok);
}

}  // namespace

int main() {
  TestDifferentialAgainstDoubleOracle();
  TestSingleFrameAndVarianceClamp();
  TestMalformedInputFailsClosed();
  std::cout << "M9 SIMD fusion host tests passed.\n";
  return 0;
}
