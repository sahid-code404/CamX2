# Architecture Constitution

This document is the highest-level engineering contract for CamX. A conflicting implementation,
ticket, test, or convenience is wrong until this document is changed through an explicit Tier-A
architecture migration.

## Mandatory invariants

1. Exactly one active camera owner exists: `CameraSessionController` owns every `CameraDevice`,
   `CameraCaptureSession`, session output, and camera callback dispatcher.
2. UI never opens or closes a `CameraDevice`; it issues typed intent to the runtime boundary.
3. Discovery reads metadata but never opens a `CameraDevice`.
4. RAW capture and DNG writers never open a `CameraDevice`; a bounded transaction borrows a lease
   from the session owner.
5. Camera IDs are opaque transport identifiers. Manufacturer, model, SoC, sensor name, and numeric
   ID have no production routing meaning.
6. Advertised topology is evidence, not runtime trust. Metadata, preview, and RAW trust are separate.
7. A real optical `CanonicalLens` can own multiple transport `CameraProfile` values.
8. Automatic failover may select only another profile of the same canonical lens.
9. Storage, encoding, cancellation, and MediaStore failures never damage route or camera trust.
10. Every asynchronous callback is generation-bound. A stale callback closes only resources it owns
    and can never mutate active selection, session, surface, capture, or UI state.
11. Persistent settings are never queried from camera open, switch, request-building, capture, or
    first-frame paths. Runtime reads an immutable in-memory snapshot.
12. Normal startup is cache-first. Valid hot cache may bypass complete discovery until after frame 1.
13. Network, OTA, deep discovery, and diagnostics formatting wait until a verified first frame.
14. Idle photo preview attaches only display outputs. RAW is transaction-scoped and destroyed after
    capture before preview restoration.
15. Every queue, timestamp index, metrics buffer, trace, worker pool, and native buffer pool has an
    explicit capacity and overflow policy.
16. Native resources have deterministic RAII ownership; bare owning pointers and undocumented JNI
    global references are forbidden.
17. Ordinary camera and debug-OTA behavior uses no `Service`, `ForegroundService`, `JobService`, or
    `WorkManager`.
18. The `devOta` build stays debuggable, keeps package `com.sahidcode404.camx`, and uses one permanent
    committed development signer with API-23-compatible v1 and v2 APK signatures. Regenerating it
    breaks the channel.
19. State, ownership, topology, cache identity, JNI, RAW pairing, signer verification, and guard
    changes require Tier-A review.
20. The application compatibility baseline is exactly Android API 23. The Gradle model, every variant,
    merged APK, development manifest, and published artifact must agree; raising the floor is an
    explicit Tier-A constitutional migration, never an incidental dependency or toolchain result.
21. `libcamx_core.so` is loadable on API 23 for every packaged ABI and has no unconditional dependency
    on a later or private platform library. Camera NDK metadata and media-image ownership begin at API
    24; hardware-buffer ownership begins at API 26. A device API level alone never makes an optional
    native backend available: implementation, public-library, and complete-symbol probes must agree.
22. Android Camera2 in Kotlin is the only camera device/session control plane on every supported API.
    Optional native code may supply measured metadata or data-plane work but cannot open a camera,
    become a second owner, or load a private/vendor camera library.
23. Rejection of requested preview options is nonstructural evidence and permits exactly one new
    `SAFE_BASELINE` configuration attempt without changing persistent trust. Rejection of that known
    safe baseline is structural, rejects preview trust for that profile, and alone permits
    same-canonical failover.
24. Generation equality is necessary but not sufficient for asynchronous mutation. A callback must
    consume the exact owner-, stage-, intent-, surface-, attempt-, and generation-bound one-shot
    permit before it may publish state or adopt a delivered resource. Stale resources close exactly
    once; invalidation happens before cleanup, and cleanup completion cannot overwrite newer intent.
25. CI build, emulator runtime, and physical-device results are separate evidence classes. A green
    build or static APK/ELF inspection cannot be reported as API-23 launch evidence or physical-camera
    compatibility.
26. Phase 3 processing consumes acquisition outputs behind `ImageProcessor`; it cannot change the
    Phase-2 camera ownership model without an explicit architecture migration.

## Computational RAW Revision 2 extension

27. The accepted computational imaging engine is `EvidenceConstrainedImagingEngine`. Photo and video
    share one scientific core; acquisition/temporal/deadline/persistence policies may differ.
28. Sensor truth means a public interpretable sensor-domain representation with no CamX
    sample-changing processing. It never promises an unknowable absence of sensor/HAL corrections.
29. Acquisition truth is typed as `InterpretableSensorDomain`, `CameraProcessed`, or
    `OpaqueTransport`. Processed and opaque inputs can never be relabeled into sensor truth.
30. `SensorNegative`, `ComputationalNegative<FusedCfaRadiance | LinearSceneRgb>`, and
    `ProcessedSourceMaster` are distinct products. `LinearSceneRgb` is never remosaiced merely to
    appear RAW.
31. One reconstruction contains exactly one canonical optical lens and one compatible historical
    route/profile/calibration epoch unless a future multi-camera Tier-A ADR defines a new product.
32. The Camera2-to-imaging boundary is a bounded, generation/permit-bound one-time handoff. After
    transfer, capture identity is immutable historical truth and cannot be re-resolved from live UI
    or topology state.
33. Sensor-mode graphs bypass every CamX sample-changing computational node. A processed-source
    fallback is legal only when `SourcePolicy` permits it and produces a processed-source artifact.
34. Graph edges/nodes are representation-, shape-, calibration-, temporal-, uncertainty-, precision-,
    resource-, backend-, and version-aware. An illegal or unbounded graph fails admission before
    capture.
35. Worst-case resource reservation precedes acquisition. OOM probing, unbounded full-resolution
    frame stacks, unbounded metadata, and silent arbitrary Sensor-video drops are forbidden.
36. Deterministic scalar/reference algorithms define required semantics. SIMD, Vulkan, and AI are
    optional qualified providers and cannot redefine source or product truth.
37. Measurement, calibration, noise, visibility/occlusion/inlier support, motion ambiguity, and
    uncertainty are explicit scientific state. Missing evidence lowers confidence or triggers a
    measured fallback; it is never replaced by invented detail.
38. `SensorDngWriter` and `ComputationalDngWriter` are separate contracts. CFA versus Linear DNG
    follows actual output semantics, and no metadata is fabricated for decoder acceptance.
39. `RawVideoContainerContract` is frozen independently of any byte-container implementation.
    MCAP/CXRB remains provisional until its M2A durability/performance/recovery gate passes.
40. `RawVideoCodecContract` is frozen with mandatory reversible `PACKED_NONE`; compressed codecs are
    provisional and admission never relies on an assumed compression ratio.
41. Source deletion occurs only after a committed, reopened, verified output and durable retention
    commit. Storage, backend, algorithm, thermal, or publication failure cannot damage camera trust.
42. Support claims are exact-profile evidence keyed by lens/route/profile/representation/size/FPS/
    stream combination and relevant provider/storage/software fingerprints; build success or device
    marketing identity is not physical certification.
43. Negative production stops before artistic rendering. AI remains optional, versioned,
    measurement-constrained, and removable. V1 compute is bounded, foreground lifecycle-scoped and
    in-process; separate-process/background execution requires later explicit proof and ADRs.

## Simplicity test

Every abstraction must state the state or resource it owns, the invariant it enforces, and its
failure boundary. A pass-through abstraction that answers none of those is removed. Immutable value
objects and pure policies are not bureaucracy: they prevent identity confusion and make platform
behavior testable without a device.

## Universal compatibility exceptions

A device-specific compatibility rule is allowed only after the generic capability path was tried,
hardware evidence proves the need, scope is narrowly fingerprinted to observable behavior, the
reason and expiry condition are recorded in an ADR, and tests prove unrelated environments cannot
inherit it. Brand or camera-ID dispatch is never an acceptable shortcut.
