# ADR-021: Deterministic reference semantics and backend separation

Status: Accepted

## Context

SIMD, Vulkan, and learned acceleration can improve performance but cannot be allowed to redefine the algorithm or product truth.

## Decision

A deterministic scalar/reference implementation defines each required scientific algorithm. Optimized providers implement the same semantic contract and are admitted only after differential correctness plus measured performance/resource qualification.

Provider failure falls back to another qualified implementation or a typed job failure; it cannot alter source/product semantics.

## Consequences

Correctness is testable without a particular GPU/SoC. Optional optimization remains removable and API 23 retains a complete path.
