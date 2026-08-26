# Computational RAW Logical Module Boundaries

Status: Accepted ownership map; physical Gradle/native extraction is milestone-driven

Revision 2 names future ownership boundaries, not an instruction to create dozens of empty modules at M0. CamX2 therefore keeps the accepted CAMX-108 production layout unchanged during M0 and uses these names as dependency targets for later milestones.

## Logical ownership map

| Logical boundary | Responsibility |
| --- | --- |
| `:app` | UI, settings, lifecycle, existing camera composition |
| `:camera-contract` | immutable camera/acquisition intent and identity contracts |
| `:camera-controller` | existing `CameraSessionController` sole Camera2 ownership |
| `:acquisition-model` | representation-typed acquired evidence descriptors |
| `:acquisition-handoff` | bounded generation-bound one-time lease transfer |
| `:capture-planner` | bounded `CaptureRecipe` and admission inputs |
| `:capability-certification` | evidence levels and exact-profile support keys |
| `:imaging-model` | scientific sample/measurement/product contracts |
| `:calibration` | calibration interpretation and confidence |
| `:noise` | declared shot/read/FPN/noise models |
| `:motion` | motion/rolling-shutter evidence |
| `:alignment` | qualified alignment algorithms/contracts |
| `:confidence` | visibility/occlusion/support/uncertainty state |
| `:fusion` | evidence-constrained reconstruction/fusion |
| `:computational-negative` | `FusedCfaRadiance` / `LinearSceneRgb` product semantics |
| `:graph-ir` | immutable typed graph representation |
| `:graph-compiler` | legality, resource, causality, determinism plan compilation |
| `:resource-planner` | worst-case reservation and feasibility |
| `:scheduler` | bounded execution, queues, cancellation, deadlines |
| `:backend-api` | backend-neutral algorithm contracts |
| `:backend-reference` | deterministic scalar/reference implementations |
| `:backend-simd` | differentially qualified CPU SIMD implementations |
| `:backend-vulkan` | optional qualified Vulkan providers |
| `:raw-codec` | reversible `RawVideoCodecContract`, mandatory `PACKED_NONE` |
| `:raw-container-*` | replaceable `RawVideoContainerContract` adapter(s) after M2A |
| `:dng-export` | split Sensor/Computational DNG writer seams |
| `:openexr-export` | possible truthful linear interchange after proof |
| `:storage-transaction` | durable source/output/journal transaction semantics |
| `:recovery` | bounded crash-tail and transaction recovery |
| `:processing-manifest` | canonical hashes/provenance/versions/uncertainty ledger |
| `:photo-orchestration` | photo acquisition/reconstruction orchestration only |
| `:video-orchestration` | video segment/temporal orchestration only |
| `:thermal-policy` | measured resource/quality degradation policies |
| `:diagnostics` | bounded truth-preserving diagnostics |
| `:ai-contract` | measurement-constrained learned-provider seam |
| `:ai-model-registry` | immutable version/hash model lifecycle |
| `:ai-runtime-adapters` | optional provider/runtime adapters |
| `:quality-corpus` | immutable source and synthetic/rig corpora |
| `:quality-metrics` | integrity/scientific/temporal/uncertainty metrics |
| `:benchmarks` | capability-fingerprint performance qualification |
| `:hardware-certification` | exact-profile physical acceptance evidence |
| `:native/core` | stable native foundations and bounded ABI contracts |
| `:native/reference` | deterministic reference kernels where native is useful |
| `:native/simd` | qualified SIMD kernels |
| `:native/vulkan` | optional qualified Vulkan kernels |
| `:native/codecs` | reversible codec implementations |
| `:native/container` | selected container adapter implementation(s) |
| `:native/fuzz` | parser/codec/metadata fuzz targets |

## Mandatory dependency rules

1. Imaging may depend on immutable acquisition contracts, never on `CameraSessionController` or Camera2 control APIs.
2. Backends depend on algorithm contracts, never capture orchestration.
3. Storage cannot determine camera/profile support or mutate camera trust.
4. AI runtime adapters cannot be imported by Sensor-mode graph construction.
5. UI receives immutable snapshots and never Camera2 handles or Android `Image` ownership.
6. Camera control never waits for disk, GPU, compression, graph compilation, or long scientific work.
7. Existing one-shot RAW groundwork is extended through a handoff seam, not rewritten into a second camera engine.
8. API 23 baseline code has a complete CPU path. Optional API-24+/26+ facilities are capability-gated.
9. Each extracted module must own a real state/resource/failure boundary; pass-through modules are forbidden.

## Extraction policy

A logical boundary becomes a physical Gradle/native module only when its milestone contains enough implementation to justify a build boundary and the extraction does not alter accepted behavior. Empty architecture theater is explicitly rejected.

M1 may initially introduce representation and handoff contracts alongside the existing camera packages while tests prove ownership and behavior. Later extraction must preserve package/API semantics and pass the same differential and architecture guards.
