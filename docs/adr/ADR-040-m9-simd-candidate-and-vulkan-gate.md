# ADR-040 — M9 SIMD candidate and Vulkan proof gate

Status: Accepted software-prototype decision; physical acceptance pending

## Context

M7 established the scalar `FusedCfaRadiance` reconstruction semantics. The M9 roadmap permits optimization only after those semantics exist, in the order scalar/reference -> SIMD -> optional Vulkan. An optimized provider may not redefine representation truth, uncertainty, provenance, camera ownership, or fallback behavior, and a provider that does not demonstrate measured end-to-end benefit must not become a shipping route.

## Decision

CamX2 adds one isolated native dense inverse-variance arithmetic candidate inside the existing API-23-loadable `libcamx_core.so` boundary.

- arm64 uses NEON SIMD; ARMv7 uses NEON only when the toolchain explicitly exposes it and otherwise remains portable scalar.
- x86/x86_64 use the baseline SSE2-capable path.
- unsupported builds retain a bounded portable scalar implementation inside the candidate.
- JNI validates exact bounded frame/pixel/sample extents before native work.
- Kotlin never labels candidate output as the accepted M7 product and M7 continues to publish `GraphBackend.SCALAR_REFERENCE`.
- host-native tests compare SIMD output to an independent double-precision scalar oracle within an explicit numerical tolerance.
- Android CI must still build `libcamx_core.so` for all four ABIs against API 23 and pass the packaged load-boundary check.

The candidate is **not routed into production reconstruction** until exact-device p50/p95/p99 runtime, memory, energy, thermal and full-product differential evidence proves a benefit. This keeps the existing reference path as deterministic rollback.

Vulkan is deliberately **not implemented or enabled** by this ADR. A Vulkan provider requires a separate capability/driver/failure-isolation implementation and measured evidence; the architecture does not treat GPU availability as proof of benefit.

## Consequences

This checkpoint creates real SIMD code and a callable bounded provider without prematurely changing accepted M7 semantics. It also makes failure cheap: deleting the M9 candidate returns the tree to the scalar reference without touching Camera2, acquisition, calibration, alignment, DNG, RAW-video or UI ownership.
