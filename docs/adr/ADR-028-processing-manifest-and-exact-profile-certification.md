# ADR-028: Processing manifest and exact-profile certification

Status: Accepted

## Context

Build success, advertised Camera2 capability, or a device model name cannot substantiate scientific/sustained support claims.

## Decision

Every artifact carries/hash-binds source identities/digests, requested/resolved policy, graph/algorithm/model versions, parameters, providers/precision/fallbacks, uncertainty/calibration state, build identity, and output/manifest digests.

Support is certified by exact canonical lens + route/profile + pixel mode + representation + size + FPS/exposure policy + complete stream combination + relevant dynamic-range/color/stabilization + provider/OS fingerprint + storage class + engine/codec/container versions.

## Consequences

Evidence stages are explicit and support is never inferred from manufacturer/model identity or build success. Connection-specific external cameras require connection-scoped proof.
