#pragma once

#include <cstddef>
#include <cstdint>

namespace camx::imaging {

constexpr std::size_t kM9MaxFusionFrames = 32U;
constexpr std::size_t kM9MaxFusionPixels = 16U * 1024U * 1024U;
constexpr std::size_t kM9MaxFusionSamples = 64U * 1024U * 1024U;

enum class FusionBackend : std::int32_t {
  kPortableScalar = 0,
  kArmNeon = 1,
  kX86Sse2 = 2,
};

struct FusionStatus final {
  bool ok = false;
  FusionBackend backend = FusionBackend::kPortableScalar;
};

FusionBackend CompiledFusionBackend() noexcept;

FusionStatus FuseDenseInverseVariance(
    const float* signals,
    const float* variances,
    std::size_t frame_count,
    std::size_t pixel_count,
    float minimum_variance,
    float* fused_radiance,
    float* fused_variance,
    float* effective_sample_count) noexcept;

}  // namespace camx::imaging
