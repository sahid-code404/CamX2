# ADR-031: Bounded lifecycle-scoped in-process V1 compute

Status: Accepted

## Context

A separate native compute process can improve crash isolation but adds API-23 IPC, lifecycle, file-descriptor, GPU-context, recovery, and memory-duplication complexity that has not yet been proven.

## Decision

V1 compute is foreground lifecycle-scoped, bounded, and in-process behind a transport-neutral executor seam. It uses a fixed-capacity CPU/native pool, one ordered GPU submission actor when a qualified GPU backend exists, bounded arenas/writers, typed DAG scheduling, and deterministic cancellation/cleanup.

No `Service`, `ForegroundService`, `JobService`, or `WorkManager` is introduced merely to host compute. A separate process remains a future Tier-A prototype decision.

## Consequences

M1-M14 cannot claim true native crash isolation or guaranteed background continuation. Durable source records enable safe foreground resume/deferred work.
