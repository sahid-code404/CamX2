# ADR-012: Acquisition-independent future processing

Status: Accepted

## Context

HDR, alignment, demosaic, denoise, super-resolution, tone mapping, SIMD, and Vulkan will evolve faster
than Camera2 lifecycle and must not contaminate its ownership model.

## Decision

Acquisition produces owned `RawFrame` objects. A non-copyable `RawFrameSet` owns them until one
explicit `takeFrames()` transfer into a `ProcessingGraph` of `ImageProcessor` nodes. The session
owner knows only transaction input/output and cancellation, not processor implementation.

## Consequences

Phase 3 can add scalar/NEON/Vulkan processors behind bounded queues and CPU fallback. Any need to
change camera ownership requires an explicit architecture migration rather than a processing ticket.
