# ADR-005: Immutable topology snapshots

Status: Accepted

## Context

Discovery backends complete independently; mutating shared graphs exposes partial identity/trust to
runtime readers.

## Decision

Backends return immutable evidence. A pure deterministic resolver builds a complete new
`CameraTopologySnapshot`, and the repository publishes it atomically.

## Consequences

Ordinary reads are lock-free and topology is unit-testable by permutation. Reconciliation allocates a
new bounded graph, which is acceptable off the first-frame critical path.
