#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly kotlin_dir="app/src/main/java/com/sahidcode404/camx/core/imaging/optimization"
readonly native_source="native/core/src/imaging/reconstruction_simd.cpp"
readonly native_header="native/core/include/camx/reconstruction_simd.hpp"
readonly native_test="native/core/tests/m9_simd_fusion_test.cpp"
readonly doc="docs/computational-raw/M9_SIMD_VULKAN_OPTIMIZATION.md"
readonly adr="docs/adr/ADR-040-m9-simd-candidate-and-vulkan-gate.md"

for path in \
  "$kotlin_dir/M9OptimizationModel.kt" \
  "$kotlin_dir/NativeSimdFusionCandidate.kt" \
  "$native_source" "$native_header" "$native_test" "$doc" "$adr"; do
  test -f "$path" || { echo "M9 required artifact missing: $path" >&2; exit 1; }
done

for token in \
  'MAX_FUSION_FRAMES' 'MAX_FUSION_PIXELS' 'MAX_FUSION_SAMPLES' \
  'NativeSimdFusionCandidate' 'M9DenseFusionShape' 'PORTABLE_SCALAR' 'ARM_NEON' 'X86_SSE2'; do
  rg --fixed-strings --quiet "$token" "$kotlin_dir" || {
    echo "M9 Kotlin contract token missing: $token" >&2
    exit 1
  }
done

for token in 'vld1q_f32' '_mm_loadu_ps' 'FuseDenseInverseVariance' 'kM9MaxFusionSamples'; do
  rg --fixed-strings --quiet "$token" "$native_source" "$native_header" || {
    echo "M9 native SIMD token missing: $token" >&2
    exit 1
  }
done

rg --fixed-strings --quiet 'src/imaging/reconstruction_simd.cpp' native/core/CMakeLists.txt || {
  echo 'M9 native source is not compiled into libcamx_core.' >&2
  exit 1
}
rg --fixed-strings --quiet 'm9_simd_fusion_test.cpp' scripts/test-native.sh || {
  echo 'M9 native host differential test is not wired into the host runner.' >&2
  exit 1
}
rg --fixed-strings --quiet 'Vulkan is NOT IMPLEMENTED and NOT ENABLED' "$doc" || {
  echo 'M9 documentation must preserve the Vulkan proof gate.' >&2
  exit 1
}
rg --fixed-strings --quiet 'GraphBackend.SCALAR_REFERENCE' "$doc" || {
  echo 'M9 documentation must preserve scalar production provenance until physical proof.' >&2
  exit 1
}

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader' \
  "$kotlin_dir" "$native_source" "$native_header"; then
  echo 'M9 optimization boundary violation: camera ownership/control reference found.' >&2
  exit 1
fi

if rg --line-number 'VkInstance|vkCreateInstance|VulkanProvider' "$kotlin_dir" "$native_source" "$native_header"; then
  echo 'M9 Vulkan implementation appeared before its separate proof gate.' >&2
  exit 1
fi

echo 'M9 SIMD candidate and Vulkan proof-gate verification passed.'
