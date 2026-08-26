# CamX native core

The native core is an Android API-23-loadable C++20 library for measured data-plane work. Current
executable foundations are an API-neutral move-only owner template, a bounded timestamp index,
bounded buffer pool, primitive trace ring, resource counters, one coarse JNI health snapshot, and
host tests. The baseline target links no Camera NDK, media image, hardware-buffer, private, or
vendor library.

Source ownership is organized by concern:

- `src/jni`: versioned coarse Kotlin/native boundary;
- `src/memory` and `src/buffer`: deterministic owners, counters, bounded pools;
- `src/trace`: primitive hot-path diagnostics;
- future `src/camera` and `src/metadata`: optional public Camera NDK evidence only (API 24+), never
  device control;
- future `src/topology`: measured evidence normalization helpers, not global policy;
- future `src/raw`: optional AImage (API 24+) and AHardwareBuffer (API 26+) transaction ownership;
- future image/alignment/demosaic/fusion/denoise/superres/tonemap/vulkan directories are created only
  when their implementation ticket begins.

Java Camera2 in `CameraSessionController` remains the universal policy oracle and authoritative
Android control plane. Post-23 native facilities require separate capability-gated targets and
typed unavailable/unsupported results; they may never raise the application baseline or invalidate
unrelated Java Camera2 routes. See `docs/NATIVE_MEMORY_MODEL.md` and ADR-002/ADR-008/ADR-013.
