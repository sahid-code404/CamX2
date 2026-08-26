# ADR-008: Coarse-grained JNI

Status: Accepted

## Context

JNI calls, object conversion, reference lifetimes, and exceptions have costs and leak risks. Per-field
or per-pixel calls erase native advantages.

## Decision

JNI passes validated compact batches or explicit native handles with close. Metadata normalization,
trace snapshots, counters, and future frame sets cross in one coarse operation.

## Consequences

Schemas must be versioned and bounded on both sides. Small control decisions remain Kotlin. Native
APIs are Tier A and require host/ABI tests.
