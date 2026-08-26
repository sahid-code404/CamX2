# ADR-024: Failure isolation and source-retention transaction

Status: Accepted

## Context

Camera, storage, thermal, codec, backend, algorithm, cancellation, and publication failures mean different things. Conflating them damages trust and can lose reproducible source evidence.

## Decision

Failure domains are typed and independent. Storage/backend/algorithm failure cannot mutate camera/profile trust. Processed fallback cannot masquerade as sensor output.

`DELETE_AFTER_VERIFIED_OUTPUT` deletes source only after output commit, reopen, validation, digest/manifest verification, and durable retention commit. Incomplete output never justifies source deletion.

## Consequences

Failed computation remains recoverable/retriable when source is durable. Source deletion is explicitly disclosed as a reproducibility tradeoff.
