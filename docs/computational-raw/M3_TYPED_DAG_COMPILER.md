# M3 — Deterministic Typed DAG / Compiler

Status: Implemented software checkpoint; later scientific algorithms and optimized-provider qualification remain separate evidence gates

M3 implements the dependency-ordered Revision-2 graph foundation after the green M1/M2A/M2B baselines. It converts immutable M1 source-manifest evidence into a deterministic, representation-aware, resource-proven execution plan without touching Camera2 ownership or inventing reconstruction semantics early.

## Implemented production surface

Production package:

`app/src/main/java/com/sahidcode404/camx/core/imaging/graph/`

### Typed graph IR

`GraphValueType` declares:

- representation class;
- canonical encoding;
- photometric domain;
- dimensions and valid area;
- canonical plane offsets/row strides/meaningful row bytes/pixel stride;
- stored/effective precision;
- CFA and sensor-pixel mode where legal;
- calibration identity/version/confidence and color-calibration identity;
- immutable `ManifestSourceId` lineage and temporal scope;
- uncertainty semantics.

`GraphValue` additionally declares exact canonical bytes, source binding when applicable, memory domain, and lifetime. Opaque transport is rejected before it can become a graph value. Source values bind the exact M1 canonical-raster and representation-descriptor SHA-256 digests.

`GraphNodeInvocation` declares a typed node ID, versioned algorithm ID, parameter-schema version, ordered input/output value IDs, and bounded metadata. Mutation semantics and backend capabilities are not supplied by the caller; they come from the reference algorithm catalog.

### Reference semantics

M3 deliberately implements only bit-exact infrastructure nodes:

- `reference.exact-copy`;
- `reference.exact-fork`;
- `reference.calibration-gate`.

All three preserve samples, geometry, representation, and provenance. `reference.calibration-gate` additionally proves that compiler-enforced calibration prerequisites are functional. No M5 calibration transform, M6 alignment, M7 fusion, M13 enhancement, or M14 learned prior is smuggled into M3.

`SCALAR_REFERENCE` is the only M3 backend and `NO_RANDOMNESS_ALLOWED_IN_M3_REFERENCE_PLAN` is explicit in every determinism plan.

### Compiler legality

`GraphCompiler` fails closed on:

- empty/excessive node or value counts;
- duplicate node/value IDs;
- missing inputs/outputs;
- duplicate producers;
- any attempt to produce/rewrite an immutable source binding;
- unproduced intermediates;
- dangling non-final values;
- cycles;
- unknown algorithm/version;
- malformed/unsupported parameter metadata;
- input/output type or byte-extent mismatch;
- insufficient calibration evidence;
- unsupported backend/memory domain;
- infeasible resource reservation.

Topological scheduling is deterministic with node-ID tie breaking. Declaration order does not affect semantic graph or plan hashes.

### Resource proof

The compiler treats all admitted source values as initially live, accounts exact output allocations per node, performs deterministic last-use analysis, releases non-final values only after their final consumer, retains declared final outputs, and computes:

- source bytes;
- final-output bytes;
- per-node live-before/output/workspace/peak/live-after values;
- peak live+output bytes;
- peak workspace;
- explicit safety margin;
- total reserved bytes.

Checked `Long` arithmetic is used throughout resource accounting. A plan whose reservation exceeds the caller's explicit budget is rejected before execution. The absolute M3 plan bound is a software parser/admission bound, not a claim that any phone can provide that memory.

### Determinism and manifest plan

Compilation emits two independent SHA-256 identities:

- semantic graph hash — depends on typed values, immutable source hashes, algorithms, connections, parameters, and final outputs;
- admitted plan hash — additionally binds backend assignments, liveness/releases, resource proof, and resource budget.

Changing only the resource budget changes the plan hash without changing the semantic graph hash.

The processing-manifest plan records exact source bindings, final output IDs, graph/plan hashes, and aggregate mutation truth. The determinism plan records each algorithm/version/schema/backend, determinism class, latency class, mutation flags, and the no-randomness policy.

### Scalar reference executor

`ReferenceGraphExecutor` accepts only a compiled M3 plan. Before execution it verifies every source payload's exact byte count and canonical-raster SHA-256 against immutable M1 evidence. It executes exact-copy/fork/calibration-gate semantics and releases intermediates according to the compiled last-use plan. Final outputs are immutable from the caller's perspective because byte access returns defensive copies.

The executor is a correctness oracle for M3 infrastructure. It is not the future high-performance scheduler and does not establish realtime or device-throughput capability.

## Software acceptance coverage

Tests cover:

- declaration-order-independent graph/plan hashes;
- deterministic topological tie breaking;
- 100 deterministic value/node/output declaration permutations yielding one plan;
- cycle rejection;
- type mismatch rejection;
- resource over-budget rejection;
- exact fork/copy liveness and safety-margin accounting;
- malformed node metadata rejection;
- calibration prerequisite rejection;
- duplicate producer rejection;
- unknown algorithm rejection;
- exact source/algorithm manifest binding;
- bit-exact repeatable scalar execution;
- source-digest mismatch rejection;
- missing source rejection;
- defensive output-byte ownership;
- semantic graph hash stability when only admission budget changes.

## Tier-A review fields

**Invariant:** optimized providers and future scientific nodes may not redefine source/product truth or an accepted algorithm version. No bounded plan means no execution.

**Ownership transfer:** M3 owns no camera/ImageReader resource. Historical M1 source identity/hash is copied into immutable graph bindings. During reference execution, validated input payloads are copied into execution-owned arrays; intermediates are released at last use and returned outputs use defensive accessors.

**Stale behavior:** no current UI/topology/session lookup exists. M3 consumes historical immutable source evidence only.

**Failure classification:** graph legality/calibration/resource failures stay in the graph-compile domain; source hash mismatch stays in execution-input integrity. Neither changes camera/profile trust.

**Unit tests:** focused compiler/resource/reference-execution tests plus deterministic 100-permutation coverage are part of `testDebugUnitTest`.

**CI guard impact:** `scripts/verify-m3-graph.sh` protects required M3 artifacts/semantics and forbids camera/controller/raw-video/UI/filesystem coupling. CI runs it directly after M2B.

**Hardware acceptance step:** none is claimed by M3's pure deterministic graph compiler. API-23 CI remains required. M4/M10 own physical source acquisition; M9 owns physical SIMD/Vulkan performance/differential qualification.

## Explicitly deferred

M3 does not implement burst acquisition, calibration correction, noise modeling, alignment, fusion, computational negatives, DNG writing, SIMD, Vulkan, continuous RAW video, realtime scheduling, artistic rendering, or AI.

Per the accepted dependency graph, **M4 bounded burst acquisition** is the next sequential scientific milestone on the M3 -> M4 -> M5 -> M6 -> M7 path. M10 remains blocked until M2A + M2B + M4 are all accepted.
