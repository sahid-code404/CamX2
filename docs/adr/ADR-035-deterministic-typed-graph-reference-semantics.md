# ADR-035 — Deterministic Typed Graph Reference Semantics

Status: Accepted for the M3 software reference baseline

## Context

Revision 2 requires a representation-aware typed DAG whose scalar/reference implementation defines semantics before SIMD, Vulkan, learned providers, burst reconstruction, or realtime scheduling are allowed to optimize them. M1 already provides immutable representation-typed source evidence. M2A/M2B provide replaceable storage/codec experiments, but neither may define imaging graph semantics.

M3 therefore needs a deterministic compiler boundary that can reject illegal jobs before execution, prove a bounded resident-memory plan, bind exact immutable source evidence into the future processing manifest, and give later optimized providers a reference result and algorithm identity to match.

## Decision

CamX2 adopts the following M3 baseline:

1. `GraphValueType` is the canonical graph edge truth record. It binds representation, encoding, photometric domain, dimensions/valid area, canonical plane layout, precision, CFA/sensor mode, calibration identity/confidence, source lineage, and uncertainty semantics.
2. `GraphNodeInvocation` names an immutable algorithm ID/version and parameter-schema version. Callers do not get to invent mutation flags or backend semantics; those come from the frozen reference catalog.
3. `GraphCompiler` canonicalizes declaration order, rejects duplicate/missing/unproduced/dangling values, rejects source rewrites, proves acyclicity, validates reference-node type/calibration contracts, computes last-use liveness, and rejects a plan whose worst-case resident bytes plus explicit safety margin exceed the supplied budget.
4. The M3 reference catalog contains only bit-exact infrastructure semantics: `reference.exact-copy`, `reference.exact-fork`, and `reference.calibration-gate`. They do not change samples, geometry, representation, or provenance. Scientific reconstruction algorithms remain future milestones.
5. `SCALAR_REFERENCE` is the only M3 backend. No optimized provider may later redefine these algorithm IDs; an optimized implementation must be differentially qualified against the frozen reference semantics.
6. The compiler emits separate semantic graph and admitted-plan SHA-256 hashes. Resource budgets affect the plan hash, not the semantic graph hash.
7. The emitted determinism plan explicitly records `NO_RANDOMNESS_ALLOWED_IN_M3_REFERENCE_PLAN`, exact algorithm versions, backend choices, and mutation flags. The emitted processing-manifest plan binds exact source raster/descriptor hashes and final output identities.
8. `ReferenceGraphExecutor` executes only a successfully compiled plan, verifies source payload SHA-256 before admission, uses exact byte copies for the M3 reference algorithms, releases intermediates only at compiler-proven last use, and returns defensive output copies.

## Invariants

- `CameraSessionController` remains the sole Camera2/ImageReader owner.
- M3 has no Camera2, Android `Image`, container, codec, UI, disk, GPU, SIMD, or AI dependency.
- Opaque transport cannot enter the graph.
- A source binding cannot be produced or rewritten by a graph node.
- No bounded resource proof means no executable plan.
- M3 reference algorithms are bit-exact and have no randomness.
- Later M4–M14 work may add new versioned algorithms/providers, but cannot silently alter an accepted algorithm's semantics or source/product truth.

## Ownership transfer

M3 owns no camera resource and no Android `Image`. Immutable M1 source records are copied into graph source bindings by identity/hash. Reference execution copies caller-provided source bytes into execution-owned arrays only after exact byte-count and SHA-256 validation. Intermediate arrays are released at compiler-proven last use; returned output accessors make defensive copies.

## Stale behavior

M3 never queries current camera selection, topology, session, or UI state. Stale acquisition evidence is expected to have failed M1 admission already. The graph is bound only to historical immutable `ManifestSourceId`, raster digest, representation digest, and typed source metadata.

## Failure classification

Graph-shape/type/calibration/resource failures remain graph-compilation failures. Source-payload digest failures remain execution-input integrity failures. Neither failure class mutates camera/profile trust, source certification, topology, storage trust, or active selection.

## Unit tests

M3 tests cover declaration-order determinism, a 100-permutation deterministic corpus, cycle rejection, type mismatch, duplicate producers, unknown algorithms, malformed node metadata, calibration insufficiency, exact liveness/resource accounting, over-budget rejection, source/algorithm manifest binding, bit-exact repeatable execution, source digest mismatch, missing source input, and defensive output ownership.

## CI guard impact

`scripts/verify-m3-graph.sh` is a Tier-A guard. CI runs it after M2B and before the existing camera-shell guard. The guard requires the M3 implementation/tests/ADR, checks core frozen tokens and acceptance-test names, and rejects Camera2/controller/raw-video/UI/filesystem dependencies from the M3 production package.

## Hardware acceptance step

M3 itself is deterministic pure CPU/JVM graph semantics and does not make a physical camera-performance claim. API-23 build/test coverage remains mandatory. Physical SIMD/Vulkan/backend qualification belongs to M9; physical burst/source acquisition belongs to M4/M10. No M3 green build is evidence of those later hardware capabilities.

## Rollback

The entire M3 graph package is downstream of M1 immutable source evidence and does not alter existing preview/AUX/one-shot RAW behavior. It can be removed without changing camera ownership, acquisition truth, M2A/M2B formats, or existing install/OTA identity.
