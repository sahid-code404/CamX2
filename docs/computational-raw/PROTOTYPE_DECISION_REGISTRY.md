# Computational RAW Prototype Decision Registry

Status: M0 registry — implementation choices below are deliberately not frozen

The semantic architecture is accepted independently of these technologies. A candidate may lose its experiment without reopening source truth, camera ownership, product semantics, or the typed graph architecture.

| Decision | Current state | Gate before acceptance |
| --- | --- | --- |
| MCAP-based CXRB | PROVISIONAL / REFERENCE PROTOTYPE IMPLEMENTED / PHYSICAL EVIDENCE PENDING | M2A mobile durability, bounded-memory, throughput, corruption-radius, recovery, large-file, and MCAP-comparison evidence |
| compressed RAW-video codec/default | PROVISIONAL / RICE_DELTA_BYTE REFERENCE PROTOTYPE IMPLEMENTED / PHYSICAL PARETO EVIDENCE PENDING | M2B bit-exact corpus + API-23/ABI + online throughput + energy/thermal/storage Pareto evidence; compare every serious candidate against `PACKED_NONE` |
| `PACKED_NONE` | FROZEN baseline | Mandatory reversible admission path; not optional |
| DngCreator for matched sensor-domain cases | NEEDS PHYSICAL PROOF | M8A per-profile metadata/raster/decoder validation |
| packed RAW through DNG tooling | PROVISIONAL / NEEDS PROTOTYPE | Independent unpack/CFA/metadata cohort |
| exact `ComputationalDngWriter` implementation | PROVISIONAL / NARROW DIRECT FLOAT-CFA REFERENCE IMPLEMENTED / EXTERNAL DECODER EVIDENCE PENDING | M8B named-decoder matrix, real exact-profile calibration/source evidence, security review, and comparison against pinned/pruned SDK or standards-neutral master where useful |
| computational DNG interoperability | NEEDS PHYSICAL PROOF | Real source/calibration and named decoder matrix |
| OpenEXR/internal computational master fallback | PROVISIONAL | M8B truthful master-format decision, especially for future `LinearSceneRgb` products |
| SIMD providers | PROVISIONAL / REAL NATIVE DENSE-FUSION CANDIDATE IMPLEMENTED / PHYSICAL PARETO EVIDENCE PENDING | M9 full-product differential correctness plus measured exact-device p50/p95/p99, memory, energy and thermal benefit before production routing |
| Vulkan providers | NEEDS PROTOTYPE + PHYSICAL PROOF / NOT IMPLEMENTED | M9 feature/driver/API/correctness/performance/energy proof after a useful CPU baseline |
| direct AHardwareBuffer ingest | NEEDS PROTOTYPE + PHYSICAL PROOF | API-26+ format/import/copy/energy evidence; never API-23 assumption |
| segment/tile/checkpoint dimensions | PROVISIONAL | container/codec/resource measurements |
| float precision profiles | PROVISIONAL / M8B FLOAT32 CFA REFERENCE USES THIS PROFILE | numerical error, uncertainty, storage, and named-decoder interoperability proof |
| separate compute process | PROVISIONAL FUTURE TIER-A | IPC/lifecycle/death/GPU/API-23 prototype; must preserve zero Camera2 imports |
| background execution mechanism | PROVISIONAL | separate Android lifecycle/product-policy ADR |
| sustained sensor-RAW-video ownership extension | NEEDS TIER-A DESIGN + PHYSICAL PROOF | M10 extension through sole controller, without second camera engine |
| AI providers | PROVISIONAL | M14 only; classical deterministic fallback and calibrated OOD/uncertainty required |

## Physical-proof-only claims

The following are never inferred from build success or marketing identity:

- per-profile public sensor interpretation and HAL correction behavior;
- sustained RAW rates and exact stream combinations;
- storage durability and reserve margin;
- camera/gyro/OIS timestamp mapping;
- calibration/noise/alignment quality;
- external decoder interoperability;
- memory/energy/thermal steady state;
- realtime causal deadline sustainability.

Every accepted implementation ADR must cite its benchmark/corpus/device evidence and retain a rollback path.
