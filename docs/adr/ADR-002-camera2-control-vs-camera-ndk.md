# ADR-002: Camera2 control plane; selective Camera NDK data plane

Status: Accepted

## Context

Camera NDK still uses Android CameraService/HAL. Moving lifecycle and UI-facing orchestration to JNI
does not bypass the platform and can obscure resource failures.

## Decision

Keep lifecycle, permission, Surface, and Camera2 device/session control in Kotlin. Use public NDK/C++
for independently valuable metadata evidence, bounded RAW/buffer ownership, primitive traces, and
measured future image kernels. JNI is batched and coarse.

## Consequences

NDK cannot call private vendor libraries or become a second camera owner. Native adoption requires
benchmarks and RAII/leak tests, not an assumption that C++ is faster.
