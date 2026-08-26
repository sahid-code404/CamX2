# Computational RAW Frozen Contracts

Status: Accepted at M0

This document is the normative contract index for the Revision 2 `EvidenceConstrainedImagingEngine`. Implementation details may change behind these seams; the semantics may not change without a Tier-A architecture migration.

## 1. Acquired representation hierarchy

```text
AcquiredRepresentation
├── InterpretableSensorDomain
│   ├── MosaicSensorSamples
│   ├── MonochromeSensorSamples
│   └── FullColorSensorSamples
├── CameraProcessed
│   ├── P01010
│   ├── P21010
│   └── Yuv4208
└── OpaqueTransport
    ├── RawPrivateToken
    └── PrivateSurfaceToken
```

Every representation descriptor includes the information required to interpret its canonical raster: format/packing, stored bits/effective range, dimensions, active area, plane/stride semantics, CFA pattern/phase where applicable, black/white semantics, sensor pixel mode, color/calibration identity, and provenance.

Hard rules:

1. `OpaqueTransport` never enters CPU/GPU reconstruction, DNG writing, or a guessed RAW decoder.
2. `CameraProcessed` provenance survives every conversion.
3. A representation change during video starts a new explicit representation epoch/segment.
4. A source set contains exactly one canonical optical lens, compatible route/profile epoch, and compatible calibration unless a later multi-camera ADR explicitly creates a different product.

## 2. Product hierarchy

```text
SensorNegative<S : InterpretableSensorDomain>
ComputationalNegative<S : InterpretableSensorDomain, P>
  P = FusedCfaRadiance | LinearSceneRgb
ProcessedSourceMaster<S : CameraProcessed, P>
```

A `SensorNegative` requires proof that no CamX sample-changing node ran. Lossless unpacking, padding removal, endian normalization, hashing, and containerization are storage/representation operations only when they preserve numerical samples exactly.

A `ComputationalNegative` is evidence-constrained reconstruction and must retain source hashes, reconstruction metadata, uncertainty, graph versions, and whether any learned prior changed pixels.

A `ProcessedSourceMaster` may use the same scientific families where meaningful but is always disclosed as processed-source output.

## 3. Immutable ImagingIntent

```text
ReconstructionIntent = SOURCE_PRESERVING | COMPUTATIONAL
SourcePolicy         = SENSOR_DOMAIN_REQUIRED | BEST_PUBLIC_SOURCE
VideoExecution       = MAXIMUM_QUALITY_DEFERRED | REALTIME_CAUSAL
SourceRetention      = KEEP_SOURCE | DELETE_AFTER_VERIFIED_OUTPUT
```

These axes are independent. `AUTO` cannot silently alter `SourcePolicy`.

## 4. Capture identity and acquisition handoff

Acquisition identity is captured at the camera boundary and becomes immutable historical data. At minimum it binds:

- canonical lens identity;
- route/profile and physical target;
- camera-provider/connection epoch;
- selection, session, capture, and surface generations/permits where relevant;
- source representation and sensor pixel mode;
- exact dimensions/strides/active area;
- request/result timestamps and declared timebase;
- capture request/result interpretation metadata;
- calibration identity/version/confidence.

`CameraSessionController` owns Camera2 and `ImageReader`. A callback may perform only bounded validation, generation/permit checks, and one-time transfer into an acquisition lease. The lease is move-only in ownership semantics and must close/release exactly once on success, cancellation, stale generation, malformed input, or failure.

No processing component may query current UI selection/topology to reinterpret an already captured frame.

## 5. RepresentationTruthGate

The handoff is classified into exactly one semantic representation class. The gate may reject evidence that cannot be interpreted safely. It may not infer sensor truth from camera IDs, brand/model tables, opaque private formats, or the mere presence of a stream.

`RAW_SENSOR`, public packed RAW10/12/14 where supported and validated, and other public interpretable sensor-domain representations are eligible sensor evidence. Effective precision, calibration completeness, sensor mode, cadence, bandwidth, thermals, and storage cost—not nominal bit count alone—drive planning.

## 6. CaptureRecipe contract

A `CaptureRecipe` is bounded before capture. It declares exact requests, maximum frame count, maximum bytes, maximum capture duration, termination criteria, resource reservation, evidence requirements, and legal fallbacks. Fixed magic frame counts are forbidden.

Planner evidence can include luminance/highlight clipping, predicted shadow SNR, exposure/gain/frame duration, focus, gyro/subject motion, rolling-shutter evidence, flicker evidence, calibration/noise confidence, RAM/storage/thermal state, and requested quality.

## 7. Measurement model

Frames are noisy measurements, not normalized bitmaps. Reconstruction preserves a declared observation model spanning motion/rolling shutter, optics/blur, CFA/spatial calibration, exposure/gain, black offset, clipping/quantization, and noise. Visibility/inlier support and uncertainty are explicit state, not arbitrary merge masks.

Artistic white balance, contrast, saturation, tone curves, and look rendering remain outside negative production.

## 8. ComputationalNegative pixel semantics

### FusedCfaRadiance

Legal only when all contributing evidence shares one physical sensor, CFA, mode, and color basis; output is 1× on a real reference sensor grid; each output site estimates the actual CFA color at that site; and no joint demosaic, super-resolution, virtual global-shutter geometry, or other full-color reconstruction occurred.

### LinearSceneRgb

Required when joint demosaic/fusion estimates all colors, super-resolution changes the sampling grid, rolling-shutter/geometry reconstruction changes the grid, a full-color sensor is reconstructed, or compatible sensor modes require a full-color common basis.

Never remosaic `LinearSceneRgb` just to produce a Bayer-looking file.

## 9. Uncertainty and provenance

A computational product must not collapse uncertainty into a single opaque confidence number. The canonical manifest can represent, as applicable:

- per-channel/radiometric variance;
- saturation and underflow censoring;
- visibility/occlusion probability;
- robust inlier probability;
- motion ambiguity/covariance;
- effective sample support;
- inverse-problem conditioning;
- calibration confidence;
- learned-provider OOD/calibration state.

Required provenance includes source frame IDs/hashes, requests/results, timebase evidence, recipe/policy, graph hash, node/algorithm versions, parameters, precision/backend, fallbacks, build commit, deterministic class/seeds, output digest, and manifest digest.

## 10. Typed graph IR

Every edge declares:

`representation + encoding + photometric domain + dimensions/valid area/strides + calibration/color identity + capture/temporal identity + uncertainty semantics + memory domain/lifetime`.

Every node declares:

- exact input/output types;
- algorithm and parameter-schema version;
- calibration/confidence prerequisites;
- deterministic/numerical contract;
- spatial halo and temporal look-back/look-ahead;
- legal precision;
- supported backends;
- memory/workspace formula;
- latency class;
- legal fallback;
- sample/geometry/representation/provenance mutation flags.

Compiler admission validates source truth, identity, shape, representation, precision, calibration, temporal causality, algorithm variant, tiling/halo, backend qualification, buffer liveness, native/GPU reservation, queue limits, deadline/thermal/storage/energy feasibility, and manifest/determinism plans. No bounded plan means no capture.

## 11. Backend contract

The deterministic scalar/reference implementation defines algorithm semantics. SIMD, Vulkan, and learned providers are optimizations/providers only after differential qualification. Backend failure selects another qualified implementation of the same semantic algorithm or fails the job; it cannot change source truth or product type.

API 23 remains the complete baseline. Later platform facilities such as Vulkan/AHardwareBuffer are optional capability-gated optimizations, never universal assumptions.

## 12. Admission, memory, queues, and cancellation

Before acquisition, reserve the worst-case admitted plan including live inputs/outputs, temporaries, halos, temporal state, queues, CPU/native pools, GPU allocations if used, codec/container workspace, output bytes, writer state, and safety margin.

Rules:

- no OOM probing;
- no unbounded frame stacks or metadata arrays;
- full-resolution data is spooled/streamed/tiled where necessary;
- queue overflow has a typed policy;
- sensor-video cannot silently discard arbitrary frames;
- realtime computational mode preserves the source spool first, then degrades/defer compute, then stops if truth cannot be preserved;
- diagnostics/UI may conflate/drop only non-truth-bearing telemetry;
- cancellation is cooperative at bounded node/tile boundaries and never changes ownership semantics.

## 13. Compute job state

```text
PLANNED
  -> ADMITTED
  -> ACQUIRING
  -> INGESTED_OR_DURABLE
  -> EXECUTING
  -> WRITING
  -> VERIFYING
  -> COMMITTED

Any active state -> CANCELLED | FAILED -> deterministic cleanup
```

The compute state machine is independent of camera state. Preview restoration may wait for acquisition resources to be released or durably spooled, not for reconstruction completion.

## 14. Failure-domain isolation

Acquisition, camera transients, capture-integrity failure, stale generations, calibration insufficiency, infeasible plans, memory admission, thermals, backend/provider failure, algorithm failure, compression, storage, container recovery, and hotplug are separate failure domains.

A failure in one domain cannot silently mutate trust in another. In particular:

- storage failure cannot mark a sensor/profile unsupported;
- backend failure cannot damage camera trust;
- cancellation cannot mutate active selection;
- thermal pressure cannot silently switch Sensor RAW to YUV;
- a processed fallback creates a processed-source artifact type.

## 15. Retention transaction

`DELETE_AFTER_VERIFIED_OUTPUT` never means capture without a durable safety path. Source deletion is legal only after the output is committed, reopened, structurally/cryptographically validated, and the retention transaction itself is durably committed.

Source deletion reduces future reproducibility; the UI/manifest must disclose that fact.

## 16. DNG contracts

`SensorDngWriter` and `ComputationalDngWriter` are separate seams.

- Sensor CFA DNG uses genuine sensor-domain CFA semantics and only validated interpretation metadata.
- Computational CFA DNG is legal only for `FusedCfaRadiance` on a genuine CFA grid, with output-derived levels/noise semantics.
- Linear DNG uses `LinearSceneRgb` and no CFA tags.
- P010/YUV/`ProcessedSourceMaster` is never labeled Sensor RAW or Computational RAW.
- DNG metadata is never fabricated just to satisfy a decoder.
- standards validity and external decoder interoperability are independent acceptance gates.

The exact computational DNG implementation remains an M8B prototype choice.

## 17. RAW-video codec contract

`PACKED_NONE` is the mandatory reversible admission-safe baseline. It serializes meaningful canonical sample rows/planes, excluding undefined padding. Descriptor plus payload must reconstruct the exact canonical raster.

Every codec:

- is sample/precision/representation/calibration/CFA/dimension/uncertainty preserving;
- has no Camera2 or container-specific API dependency;
- declares family/version/parameters/pretransform, lengths, checksum, and decoded-raster digest;
- is independently decodable by frame, and by tile/strip only when advertised;
- has bounded workspace and output expansion;
- validates all dimensions/counts/windows before allocation;
- has a maintained compatible decoder/migration path.

Compressed codecs remain provisional until M2B. Admission reserves `PACKED_NONE` even when compression is attempted.

## 18. RAW-video container contract

Stable seam:

`product -> RawVideoRecordAssembler -> RawVideoCodecContract -> EncodedFrameLease -> RawVideoContainerContract`.

The container contract freezes product tracks, uint64 frame ordinals, explicit sensor/host/normalized timestamps, timebase mapping/uncertainty, exact representation descriptors, bounded public Camera2 interpretation metadata, hashes/provenance/uncertainty, representation/codec epochs, sequential append, layered integrity, independently recoverable segments, 64-bit offsets, bounded parsers/indexes/recovery, explicit gaps/discontinuities, version negotiation, and storage-capability declarations.

MCAP/CXRB remains an M2A candidate, not a frozen implementation.

## 19. Certification contract

A support claim is keyed by the exact combination of canonical lens, route/profile, sensor pixel mode, source format, size, FPS/exposure policy, complete stream combination, dynamic-range/color-space/stabilization settings, camera-provider/OS fingerprint, storage target class, and acquisition/graph/codec/container versions.

Evidence stages are monotonic and explicit: discovery/advertisement does not imply configuration proof; configuration does not imply valid frames; frames do not imply sensor-photo or sustained-video certification; build success does not imply physical compatibility.

## 20. AI trust boundary

AI is optional. It may estimate scene/noise/motion/visibility/quality/OOD state or supply a declared reconstruction prior within deterministic bounds. It may never alter Sensor-mode samples, relabel processed sources as RAW, change canonical lens/route identity, fabricate calibration, erase clipping/occlusion evidence, claim prior-generated detail as measured resolution, bypass resource limits, or become the only implementation of a required algorithm.

Every learned node is versioned/hash-addressed and records runtime/backend/precision, parameters, calibrated confidence/OOD state, observation residual, fallback, and whether a learned prior changed pixels.

## 21. Rendering boundary

Negative production stops before artistic rendering. Looks, creative tone mapping, saturation, contrast, and aesthetic sharpening are downstream render metadata/exports and cannot contaminate source or computational-negative truth.
