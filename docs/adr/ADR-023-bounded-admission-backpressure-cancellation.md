# ADR-023: Bounded admission, backpressure, and cancellation

Status: Accepted

## Context

High-resolution RAW bursts/video can exceed mobile RAM/storage/thermal budgets by orders of magnitude if queues and frame stacks grow reactively.

## Decision

Worst-case reservation precedes acquisition. Every queue, source slot, native/GPU arena, temporal state, workspace, output, codec/container buffer, and writer state has an explicit bound and overflow policy. OOM probing and arbitrary Sensor-video frame dropping are forbidden.

Cancellation is generation/job-bound, cooperative at bounded work units, and ownership-safe.

## Consequences

Infeasible jobs fail admission or choose a predeclared smaller valid plan. Backpressure preserves source truth before optional computation.
