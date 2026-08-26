# Computational RAW Dependency-Ordered Implementation Roadmap

Status: Accepted roadmap

The roadmap protects working CamX behavior by separating semantic freeze, real-source evidence, replaceable implementation experiments, deterministic scientific correctness, and only then optimized/advanced modes.

## Dependency graph

```text
M0 -> M1
M1 -> {M2A, M2B, M3, M8A}
M3 -> M4 -> M5 -> M6 -> M7
M7 -> {M8B, M9}
M2A + M2B + M4 -> M10
M7 + M10 -> M11
M9 + M10 + M11 -> M12
M7 + M11 -> M13 -> M14
```

`M8A` may run immediately after M1. No milestone may weaken the sole-`CameraSessionController` invariant.

## M0 — Revision 2 semantic freeze

**Goal:** freeze truth terminology, ownership, product/representation types, state meanings, replaceable contracts, resource rules, and evidence gates.

**Allowed:** architecture/contract/test-specification documents and architecture guards.

**Forbidden:** production algorithm/capture expansion or selecting MCAP, compressed codec, computational DNG implementation, GPU ingest, separate worker process, or any physical support claim.

**Exit:** semantic ADR set + contract index + prototype registry + roadmap + green architecture guards. Rollback is documentation-only.

## M1 — Immutable acquisition model and source corpus

**Goal:** convert the accepted one-shot RAW handoff into representation-typed, generation-bound, one-time source evidence and an immutable benchmark corpus.

**Inputs:** matched Image/result/context from existing CAMX-108 one-shot RAW.

**Outputs:** immutable descriptors, canonical-raster hashes, timebase evidence, manifest source records, bounded ingest, stratified corpus tooling.

**Forbidden:** second camera owner; reconstruction algorithms; burst/video expansion; RAW_PRIVATE guessing; live UI/topology lookup after capture; unproven zero-copy assumptions.

**Exit:** exact transfer/close tests, stale-permit/race tests, malformed plane/stride/overflow tests, deterministic hashing, API-23 path, hardware one-shot evidence. Rollback retains the existing one-shot path.

## M2A — RAW-video container prototype

**Goal:** select or reject MCAP/CXRB against the frozen `RawVideoContainerContract`.

**Inputs:** M1 corpus + canonical schemas + `PACKED_NONE`.

**Outputs:** isolated candidate adapters, sequential reference writer, recovery tooling, fuzz harness, benchmark report, implementation ADR.

**Forbidden:** changing source/product semantics, camera ownership, graph semantics, or codec truth.

**Exit:** bounded memory/indexes, durable checkpoints, power-cut/corruption-radius recovery, 64-bit/large-file handling, API-23 support where required, sustained storage evidence. Candidate may be rejected without architecture churn.

## M2B — RAW-video codec benchmark

**Goal:** decide whether any compressed codec materially improves the end-to-end sustainability Pareto frontier.

**Inputs:** M1 canonical rasters and mandatory `PACKED_NONE`.

**Outputs:** candidate codec adapters, corpus results, selected implementation ADR(s) or an explicit decision to ship `PACKED_NONE` only.

**Hard gates:** bit-exact round trip; bounded workspace/output/runtime; independent frame decode; malformed-input safety; API-23 required paths on every ABI; online throughput margin; `PACKED_NONE` remains reserved; energy/thermal/storage benefit, not ratio alone.

## M3 — Deterministic typed DAG/compiler

**Goal:** compile immutable corpus jobs into deterministic bounded execution plans.

**Outputs:** representation-aware graph IR, graph compiler, resource proof, deterministic scalar/reference nodes, manifest/determinism plan.

**Forbidden:** optimized providers defining new semantics.

**Exit:** repeatable output, illegal graph rejection, resource proof, malformed metadata rejection, deterministic tests.

## M4 — Bounded burst acquisition

**Goal:** extend the existing controller transaction seam to produce a reserved immutable `FrameSet` for one canonical lens.

**Forbidden:** algorithm shortcuts in Camera2 callbacks or a second owner.

**Exit:** generation/race suite, exact reservation, no leaks, per-profile physical burst proof, preview restoration correctness. Rollback returns to one-shot acquisition.

## M5 — Calibration and noise model

**Goal:** turn `FrameSet` into calibrated measurement frames with explicit calibration/noise confidence.

**Outputs:** black/white semantics, CFA/active-area interpretation, matrices/illuminants where valid, shot/read/FPN model contracts, reference implementations.

**Exit:** dark/flat/linearity corpus residual and confidence gates. No AI.

## M6 — Alignment, visibility, occlusion, uncertainty

**Goal:** estimate motion and which measurements are valid for reconstruction.

**Outputs:** classical reference alignment, rolling-shutter/motion evidence, visibility/occlusion/inlier support, uncertainty propagation.

**Exit:** synthetic truth plus real residuals, catastrophic-failure percentile limits, deterministic fallback to smaller subsets/reference.

## M7 — Computational photo reconstruction

**Goal:** reconstruct truthful `FusedCfaRadiance` or `LinearSceneRgb` negatives from accepted evidence.

**Forbidden:** artistic rendering, fake scale claims, remosaicing, hallucinated detail.

**Exit:** measured quality gain versus the same single RAW and prior reference graph, no ghost/false-detail regression, bounded memory, uncertainty/provenance complete. Reference-frame fallback remains valid.

## M8A — Sensor DNG validation

**Goal:** independently certify truthful sensor DNG writing per exact profile/representation.

**Exit:** raster/metadata round trip and named standards/decoder validation. Failure disables that exact DNG path without damaging sensor acquisition trust.

## M8B — Computational DNG/interchange prototype

**Goal:** choose a truthful writer for real M7 computational negatives.

**Candidates:** pinned/pruned DNG SDK, narrow direct writer, and standards-neutral computational master/linear interchange where DNG cannot carry semantics safely.

**Exit:** CFA-versus-Linear semantics, output-derived metadata, parser/writer security bounds, decoder matrix. Never fabricate metadata or remosaic RGB.

## M9 — SIMD, then Vulkan

**Goal:** optimize the already-correct reference graph without semantic changes.

**Order:** scalar/reference -> SIMD -> optional Vulkan.

**Exit:** node/end-to-end differential tolerances, API/capability gates, measured p50/p95/p99 speed, memory, energy and thermal benefit, driver/provider failure fallback. A provider that does not win is not shipped.

## M10 — Sensor RAW video

**Goal:** extend the sole controller with an explicit continuous sensor-source transaction/epoch and record sustained source evidence through the selected codec/container contracts.

**Forbidden:** arbitrary software frame dropping, `acquireLatestImage`-style truth loss, representation switches without explicit segment epoch, or a second camera engine.

**Exit:** exact-profile soak through thermal plateau, bounded queue high-water, storage margin, sequence-gap accounting, crash-tail recovery, p99 ingest/spool deadline, truthful stop/reconfigure behavior.

## M11 — Offline computational video

**Goal:** reconstruct a computational bundle from durable sensor-source video using bounded overlapping bidirectional windows.

**Exit:** resumable/restartable segments, temporal stability/reset gates, verified source-retention transaction, no source deletion before verified output.

## M12 — Realtime causal computational video

**Goal:** certify bounded causal variants with deterministic degradation.

**Forbidden:** future-frame use without declared fixed latency, unreserved fallback, hidden source switches, arbitrary drops.

**Exit:** exact-profile p99 deadline margin, no queue growth, backpressure/thermal/backend-failure soak, explicit stop/source epoch. Rollback is Sensor recording plus deferred processing.

## M13 — Advanced HDR / night / super-resolution

**Goal:** add separately versioned evidence-constrained recipes/nodes after the core photo/video engine is stable.

**Rules:** adaptive frame/exposure planning; real support conditions output scale; clipped/occluded/ambiguous evidence lowers confidence or falls back; no invented detail.

**Exit:** stratified HDR/night/motion/SR corpus, artifact and uncertainty gates, bounded quality-resource Pareto proof. Each recipe is feature-gated and removable.

## M14 — Optional AI providers

**Goal:** add learned estimators/priors only where they improve a frozen classical baseline.

**Forbidden:** Sensor-mode mutation, AI-only correctness, camera control, unbounded/network-required execution, fabricated calibration/detail, artistic rendering.

**Exit:** model hashes/version lifecycle, calibrated OOD/confidence, adversarial tests, provider-removal test, deterministic classical fallback, quality gain with truth preserved.

## Rollback law

Every milestone is an independent rollback boundary:

- older sources/bundles/manifests remain readable or have an explicit lossless migration;
- accepted graph/algorithm IDs remain reproducible;
- later optimization cannot alter accepted capture semantics;
- failure of a prototype technology does not reopen semantic architecture;
- no milestone weakens existing CamX preview/AUX/topology/one-shot RAW correctness or sole camera ownership.
