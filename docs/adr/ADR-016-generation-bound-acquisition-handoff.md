# ADR-016: Sole camera owner and generation-bound acquisition handoff

Status: Accepted

## Context

Scientific processing must receive real Camera2 evidence without becoming a second lifecycle owner or racing active UI/session state.

## Decision

`CameraSessionController` remains the sole Camera2/ImageReader authority. Acquisition crosses into imaging through exactly one bounded, generation/permit-bound ownership transfer whose identity is immutable after handoff.

Camera callbacks may validate and transfer a lease but perform no compression, DNG I/O, disk wait, graph compilation, or scientific work. Stale/invalid leases close exactly once.

## Consequences

Processing can evolve independently while current lens/session changes cannot rewrite historical capture truth. Any second camera owner is a Tier-A violation.
