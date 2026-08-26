# M9 — SIMD, then Vulkan optimization

Status: Software SIMD candidate checkpoint; physical performance acceptance and Vulkan remain separate gates

M9 starts from the already-correct M7 scalar reconstruction rather than inventing a second set of imaging semantics. This checkpoint implements a real native SIMD arithmetic candidate and its bounded JNI bridge, while deliberately leaving production reconstruction on the scalar reference until device evidence proves that the candidate wins.

## Implemented SIMD candidate

Production candidate code lives in `core/imaging/optimization/` and `native/core/src/imaging/`.

- `M9DenseFusionShape` proves bounded frame count, pixel count and total sample count before allocation or native dispatch.
- `NativeSimdFusionCandidate` is a coarse JNI call over dense inverse-variance fusion arrays. It uses the existing `libcamx_core.so`; it does not create another native library or camera owner.
- Native arm64 uses NEON vector arithmetic across pixels. ARMv7 uses NEON only when the NDK toolchain exposes it and otherwise takes the bounded portable scalar candidate path.
- Native x86/x86_64 use SSE2-family vector arithmetic across pixels.
- Every path preserves per-pixel source-frame traversal order. SIMD changes arithmetic provider, not source membership, representation, geometry, calibration, visibility or uncertainty meaning.
- The candidate clamps variance to the same positive lower-bound concept as M7 and returns radiance, fused variance and effective sample count only. It does not decide which evidence is visible/inlier/censored and therefore cannot override M6/M7 truth gates.
- Invalid dimensions, sample-count overflow, malformed arrays, non-finite samples or invalid minimum variance fail before native execution.

## Why it is not the shipping M7 backend yet

The roadmap explicitly says a provider that does not win is not shipped. CI can prove source bounds, ABI/API-23 compilation and arithmetic differential behavior, but CI cannot establish a real phone's p50/p95/p99 speed, memory high-water, energy or thermal behavior. Therefore `ReferenceReconstructionEngine` remains the production truth path and continues to record `GraphBackend.SCALAR_REFERENCE`.

This is intentional rather than incomplete routing: switching production provenance to SIMD before exact-device evidence would violate the frozen reference/provider separation.

## Differential validation

`native/core/tests/m9_simd_fusion_test.cpp` compares the native candidate against an independent double-precision scalar oracle over non-multiple-of-four pixel counts, multiple frames and varying variances. It also covers single-frame identity, minimum-variance clamping and malformed-input failure. The host runner builds this test with the same warning-as-error discipline as the existing native suite.

Kotlin contract tests verify exact bounded sample limits, pre-JNI malformed-array rejection and stable backend codes. Android CI additionally compiles and packages the candidate for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` under the existing API-23 load contract.

Passing these gates means **software SIMD candidate correctness**, not device optimization acceptance.

## Vulkan gate

Vulkan is NOT IMPLEMENTED and NOT ENABLED in this checkpoint. It stays optional. A later M9 continuation may add it only after the SIMD candidate is measured and only with:

- capability/API/driver gating and deterministic fallback;
- differential node and end-to-end product tolerances against the accepted reference;
- bounded GPU memory and lifecycle ownership;
- provider/driver failure recovery without camera-state mutation;
- exact-device p50/p95/p99 speed plus energy and thermal benefit.

If Vulkan does not beat the accepted CPU path on a useful exact-profile cohort, it is not shipped.

## Tier-A review fields

**Invariant:** M9 may optimize arithmetic only. It cannot change M7 representation truth, sampling grid, source membership, M5 calibration semantics, M6 visibility/occlusion decisions, uncertainty fields, camera ownership or output product labels.

**Ownership transfer:** no Camera2/ImageReader ownership enters M9. JNI receives bounded primitive arrays after scientific evidence selection; `CameraSessionController` remains the sole camera authority.

**Stale behavior:** the candidate has no live session identity and cannot look up UI/topology/session state. It operates only on caller-owned immutable numerical input for one invocation.

**Failure classification:** missing native library, unsupported SIMD, malformed input or native validation failure returns no candidate result; the accepted scalar reference remains available and unchanged.

**CI guard impact:** `verify-m9-optimization.sh` runs after M8B and checks provider isolation, real SIMD intrinsic presence, Vulkan non-claim, bounded model/test artifacts and native build integration.

**Physical acceptance step:** benchmark exact devices/profiles with warm-up and thermal plateau, collect p50/p95/p99 runtime, peak memory, energy and thermal data, and compare complete M7 output/uncertainty/provenance within declared tolerances. Only then may a winning SIMD provider be routed into production.

## Rollback

Delete the M9 optimization package, SIMD native source/header/test and its JNI entry, then remove the CMake/CI references. No M1-M8B data format, camera behavior, computational-negative semantics or DNG contract changes are required.
