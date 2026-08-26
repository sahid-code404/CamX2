# Implementation Backlog

Tickets are intentionally small and path-bounded. “Forbidden” is absolute unless a new Tier-A
architecture ticket supersedes it.

## CAMX-100A — API-23 platform and asynchronous ownership contract

- Tier: A.
- Goal: make Android API 23 the exact application/native baseline and close the preview fallback,
  callback-admission, cleanup, native-capability, and validation gaps exposed by lowering the floor.
- Allowed files: API/build/signing configuration; narrowly required API-23 compatibility fixes in
  cache/preview/runtime/update/resources; `core/camera/diagnostics/{CameraFailure,NativeCore,
  NativeCapabilities}.kt`; preview attempt models; session state/controller/mutation/permit ownership;
  their deterministic tests; `native/core/**`; OTA/native verification scripts; CI/publisher policy;
  CODEOWNERS, architecture documents, ADR-013, and this ticket.
- Forbidden files: Camera2 open/configure/repeating-request feature implementation, discovery/topology
  algorithms, RAW capture/DNG behavior, feature UI expansion, OTA networking, production signing,
  optional native backend/loader implementation, private/vendor APIs or libraries, and device quirks.
- Input contracts: Android API-23 baseline; four required ABIs; public Android/NDK API availability;
  immutable active selection, surface identity, requested/safe-baseline attempt, and built `devOta` APK.
- Output contracts: exact model/merged-APK/OTA minSdk proof; API-23-loadable baseline core; typed optional
  native status; requested-to-safe-baseline failure policy; one-shot stage/intent callback and cleanup
  permits; v1+v2-signed, hash-bound, all-ABI-verified development artifact.
- State ownership: `CameraSessionController` remains the sole engine/generation authority;
  `CameraStateMutationGate` serializes short non-suspending mutations; `CameraAsyncOwnership` owns
  current immutable intent and single-consumption permits. Native capability policy is pure.
- Resource ownership: callback-delivered resources are resolved once by `CloseOnceCameraResource`;
  pause/switch/shutdown detach before closing; cleanup continues outside the gate and only a current
  cleanup permit may publish. The baseline native core owns no Camera NDK/AImage/hardware-buffer handle.
- Error behavior: requested optional configuration rejection changes no trust and permits one new safe
  baseline attempt; safe-baseline rejection alone is structural and permits same-canonical failover.
  Missing/later/unimplemented optional native capability is typed unavailability, never app or route
  failure. Model, APK, signer, ABI, ELF, or public-symbol drift fails CI closed.
- Universality rules: branch only on Android API and probed public capabilities, never manufacturer,
  model, SoC, sensor, camera ID, or CPU vendor. Java Camera2 remains the only control plane.
- Performance constraints: no new startup loader/probe or Camera2 feature work; mutation blocks never
  suspend or close resources; callback admission is constant-time; all collections/work stay bounded.
- Tests required: API 22/23/24/26/modern capability matrix; requested/baseline policy and transition
  matrix; A/B/C callback permutations, surface replacement, pause/shutdown, stale cleanup, duplicate
  first frame/resource, exactly-once close; API-neutral native-owner lifecycle; OTA floor/signer
  rejection; lint; all-ABI ELF dependency/undefined-symbol/export checks.
- CI requirements: model minSdk assertion before compilation; JVM/native tests and `lintDevOta`; one
  all-ABI assembly; produced APK exact minSdk 23, v1+v2 signer, size/alignment, ABI/ELF/API-symbol guard;
  packaging/publisher exact-floor/hash/source checks before upload or publication.
- Hardware acceptance: GitHub API-23 emulator install/start/native-load smoke before an API-23 launch
  claim; modern-API load smoke; no physical lens/FPS/RAW/lifecycle/leak claim without the separate
  `HARDWARE_ACCEPTANCE.md` matrix.

## CAMX-101 — Atomic two-tier cache persistence

- Tier: A.
- Goal: implement bounded, checksummed hot/topology codecs and atomic files without adding camera I/O.
- Allowed files: `core/camera/cache/**`, cache tests, cache-specific docs.
- Forbidden files: `session/**`, `feature/**`, `native/**`, OTA/signing.
- Input contracts: `HotStartSnapshot`, `CameraTopologySnapshot`, `CameraEnvironmentFingerprint`.
- Output contracts: typed cache hit/miss/corrupt result and immutable in-memory snapshots.
- State ownership: `CameraCacheRepository` serializes replacements; readers own no mutable state.
- Resource ownership: repository owns streams/temp files until atomic rename and cleans failures.
- Error behavior: corruption/unknown schema is a miss; transient I/O never changes camera trust.
- Universality rules: invalidation uses observable environment only, never brand/model routing.
- Performance constraints: hot decode is independent of full topology and allocation-bounded.
- Tests required: round trip, every invalidation field, truncation, oversized counts, atomic failure.
- CI requirements: unit suite and hot-path guard; no Android Camera2 dependency in codecs.
- Hardware acceptance: compare valid-cache characteristics reads and startup median/p90.

## CAMX-102 — Minimal first-install seed discovery

- Tier: A.
- Goal: find one credible preview route without complete or deep discovery.
- Allowed files: `core/camera/discovery/**`, evidence/model tests, startup docs.
- Forbidden files: `session/**`, UI, RAW, OTA, native deep discovery.
- Input contracts: advertised public IDs and minimum immutable characteristics evidence.
- Output contracts: zero or one seed `CameraRoute` with explicit advertised trust/provenance.
- State ownership: discovery call owns only a cancellable local evidence batch.
- Resource ownership: backend owns/recycles characteristics data; it never opens a device.
- Error behavior: per-route metadata failures are recorded; total absence is recoverable.
- Universality rules: IDs opaque; ranking is capability/facing/evidence based.
- Performance constraints: bounded reads/concurrency; no complete stream/diagnostic formatting.
- Tests required: empty IDs, inaccessible route, missing facing/optics, deterministic selection.
- CI requirements: discovery purity and numeric-ID/manufacturer guards.
- Hardware acceptance: first-install preview on front/rear-primary-diverse devices, with trace counts.

## CAMX-103 — Camera2 preview owner implementation

- Tier: A.
- Goal: complete serialized open/configure/repeat/switch/pause/close inside the sole owner.
- Allowed files: `core/camera/session/**`, `runtime/**`, session tests, state/resource docs.
- Forbidden files: discovery opening, UI Camera2 imports, RAW writer, native control plane.
- Input contracts: verified `ActiveCameraSelection`, surface lease, preview configuration, settings snapshot.
- Output contracts: typed engine/UI snapshots, trace events, resource counts, first-frame gate.
- State ownership: `CameraSessionController` alone owns the typed machine and generations.
- Resource ownership: controller alone owns device/session/callback dispatcher and active surfaces.
- Error behavior: use exhaustive `CameraFailure`; stale callbacks close only delivered resources.
- Universality rules: route fields used opaquely; no vendor/device/ID assumption.
- Performance constraints: one long-lived dispatcher, one serialization gate, preview-only idle outputs.
- Tests required: transition table, reordered callbacks, rapid switch/pause, idempotent close, leak counts.
- CI requirements: owner guard must find exactly one `openCamera` call and all-ABI build stays green.
- Hardware acceptance: launch/resume/switch/contention matrix and CameX median/p90 comparison.

## CAMX-104 — Preview geometry and stream policy

- Tier: B.
- Goal: select and transform a supported responsive/high-resolution viewfinder stream.
- Allowed files: `core/camera/preview/**`, pure tests, preview diagnostics.
- Forbidden files: `session/**`, topology canonicalization, UI surface identity, native/RAW.
- Input contracts: immutable stream capabilities, view geometry, orientation/facing/mirror, FPS request.
- Output contracts: `PreviewConfiguration` and complete `PreviewGeometry`.
- State ownership: pure functions own no state.
- Resource ownership: none.
- Error behavior: no candidate is a typed unsupported configuration, never a guessed size.
- Universality rules: no fixed aspect, resolution, display size, or camera ID.
- Performance constraints: deterministic bounded sort/filter outside per-frame path.
- Tests required: all rotations/facings/aspects, min durations, high-resolution on/off, property tests.
- CI requirements: pure JVM tests and hardcoded-resolution guard.
- Hardware acceptance: crop/rotation/mirror and sustained cadence on every visible lens.

## CAMX-105 — FPS resolver and actual frame metrics

- Tier: B.
- Goal: resolve user FPS before request 1 and measure actual cadence without Compose churn.
- Allowed files: `core/camera/preview/**`, settings-facing value tests, diagnostics projection.
- Forbidden files: `session/**` request ownership, UI frame callbacks, cache schema, native.
- Input contracts: requested min/max, advertised ranges, stream minimum frame duration.
- Output contracts: `PreviewFpsResolution` and bounded `PreviewFrameMetricsSnapshot`.
- State ownership: resolver none; metrics owns one fixed primitive ring.
- Resource ownership: metrics buffer only, fixed at construction.
- Error behavior: unusual input yields deterministic fallback reason; never invent a range.
- Universality rules: active profile capabilities only.
- Performance constraints: constant-time append, no frame strings/JSON/StateFlow emission.
- Tests required: off/exact/nearest/inverted/negative/fixed/variable/duration-limited, p50/p95.
- CI requirements: unit tests and hot-path formatting guard.
- Hardware acceptance: first request metadata plus measured FPS distribution with override off/on.

## CAMX-106 — Memory-first settings persistence

- Tier: B.
- Goal: persist settings asynchronously while camera reads only immutable memory.
- Allowed files: `core/settings/**`, settings tests, `feature/settings/**` integration adapter.
- Forbidden files: `session/**`, topology, raw, native, update verification.
- Input contracts: validated `SettingsSnapshot` mutations.
- Output contracts: immediate memory snapshot and eventual persistence result.
- State ownership: `SettingsRepository` owns atomic versioned snapshot.
- Resource ownership: repository owns DataStore subscription/job and closes it with app graph.
- Error behavior: persistence failure reports diagnostics but does not roll back active memory.
- Universality rules: settings are global/profile-fingerprint keyed, never numeric-ID keyed.
- Performance constraints: no persistent read in open/switch/capture/request paths.
- Tests required: immediate visibility, ordered rapid writes, corruption/defaults, cancellation.
- CI requirements: DataStore/session import boundary guard.
- Hardware acceptance: change FPS/high-resolution then switch/launch without visible stall.

## CAMX-107 — Full multi-backend topology reconciliation

- Tier: A.
- Goal: combine Java public/physical and NDK advertised evidence conservatively.
- Allowed files: `discovery/**`, `topology/**`, native metadata parser/JNI, resolver tests.
- Forbidden files: `session/**`, UI, RAW capture, private vendor libraries.
- Input contracts: immutable bounded evidence snapshots plus previous verified topology.
- Output contracts: immutable routes/canonical lenses/profiles/trust/provenance and grouping record.
- State ownership: repository publishes; pure resolver owns no persistent state.
- Resource ownership: each backend owns public API handles until evidence construction.
- Error behavior: conflicts preserve separate lenses; backend failure does not erase other evidence.
- Universality rules: no ID similarity, focal length alone, brand, SoC, or sensor-name grouping.
- Performance constraints: post-first-frame when hot cache is valid; bounded parsing/JNI batches.
- Tests required: permutation determinism, collisions, conflicting/missing evidence, aliases, fuzz decode.
- CI requirements: host native warnings-as-errors, all ABIs, universality and native-boundary guards.
- Hardware acceptance: compare discovered/visible profiles with CameX across logical/AUX devices.

## CAMX-108 — One-shot RAW capture transaction

- Tier: A.
- Goal: configure temporary RAW, capture/pair/write exactly one image, destroy it, restore preview.
- Allowed files: `core/camera/raw/**`, necessary session transaction hooks, native RAW owners, tests.
- Forbidden files: topology canonicalization, UI navigation, OTA, future processing kernels.
- Input contracts: generation-bound `RawCaptureContext`, session lease, profile RAW capability.
- Output contracts: typed saved/cancelled/failure result and restored preview state.
- State ownership: one `RawCaptureTransaction`; session owner authorizes its state transitions.
- Resource ownership: transaction owns reader/images/pending row until explicit one-time transfer/close.
- Error behavior: bounded timeout; structural RAW failure may fail over same canonical only; output failure never does.
- Universality rules: use advertised/verified RAW data and exact active profile characteristics.
- Performance constraints: RAW absent from idle session; queues/maps bounded; no unnecessary pixel copy.
- Tests required: callback orders, overflow, stale tokens, timeouts, orientation, every MediaStore failure.
- CI requirements: ownership/static guards plus JVM/native tests and leak-counter assertions.
- Hardware acceptance: every RAW profile × rotations, cancellation/storage failure, preview restored.

## CAMX-109 — Settings and lens UI

- Tier: C (presentation) with Tier-B adapter review.
- Goal: implement accessible modern settings/lens screens against immutable UI models.
- Allowed files: `feature/settings/**`, `ui/components/**`, strings/resources, UI tests.
- Forbidden files: all `core/camera/session|runtime|topology|cache|raw/**`, native, OTA verifier.
- Input contracts: `SettingsUiSnapshot`, visible canonical-lens view data, typed UI intents.
- Output contracts: low-frequency user intents only.
- State ownership: ViewModel owns screen navigation/form draft; repository owns settings truth.
- Resource ownership: none beyond Compose lifecycle collection.
- Error behavior: inline nonmodal validation; no camera operation dialog.
- Universality rules: show optical/capability labels, never infer from ID or list index.
- Performance constraints: no camera-result/frame flow collection and stable SurfaceView identity.
- Tests required: state rendering, validation, accessibility, recomposition surface identity.
- CI requirements: UI cannot import CameraManager/Device/Session and resource/lint checks pass.
- Hardware acceptance: interaction remains responsive during preview and rapid settings changes.

## CAMX-110 — On-demand diagnostics

- Tier: B for projection; C for screen.
- Goal: format topology/traces/resource counters only when diagnostics is visible/exported.
- Allowed files: `core/camera/diagnostics/**`, `feature/diagnostics/**`, tests.
- Forbidden files: mutate session/topology/cache trust, native ownership, update signer.
- Input contracts: immutable topology, trace, metrics, resource snapshots.
- Output contracts: bounded UI/export models with build/schema identity.
- State ownership: screen/ViewModel owns presentation snapshot only.
- Resource ownership: export transaction owns destination stream.
- Error behavior: export failure is isolated from camera routing/state.
- Universality rules: diagnostics report opaque/provenance data without assigning numeric-ID roles.
- Performance constraints: no hot-path formatting or unbounded history.
- Tests required: ring overflow, ordering, redaction, snapshot consistency, export failure.
- CI requirements: hot-path string/JSON guard and unit/lint jobs.
- Hardware acceptance: ten-minute preview shows stable counters and negligible diagnostics-off overhead.

## CAMX-111 — Post-frame development OTA client

- Tier: B client/download; A verifier/signer changes.
- Goal: fetch `dev-manifest.json`, download atomically, verify, and launch user-visible installer.
- Allowed files: `core/update/**` except verifier changes require A, `feature/update/**`, OTA tests.
- Forbidden files: camera session/discovery, services/work managers, signer material regeneration.
- Input contracts: first-frame/manual trigger, signed-channel identity, manifest schema.
- Output contracts: low-frequency update state and verified local APK URI.
- State ownership: `UpdateRepository` owns one lifecycle-scoped request/download.
- Resource ownership: repository owns connection/stream/`.part`; installer owns no silent session.
- Error behavior: delete `.part` on cancel/failure; reject downgrade/package/hash/signer mismatch.
- Universality rules: Android package APIs only; no device/vendor installer branch.
- Performance constraints: never start before frame gate; bounded buffered I/O off camera dispatcher.
- Tests required: manifest bounds, redirects policy, all verification failures, cancellation/atomic rename.
- CI requirements: dev artifact manifest/signature/package verification and no-service guard.
- Hardware acceptance: forward update and invalid/cancel cases; camera stays responsive.

## CAMX-112 — Hardware acceptance recorder

- Tier: B.
- Goal: export reproducible trace/resource/result records for the hardware matrix.
- Allowed files: diagnostics/test tooling/docs; profile build configuration with Tier-A review.
- Forbidden files: production routing/identity behavior, Camera2 ownership, signer material.
- Input contracts: build identity and immutable trace/resource/config snapshots.
- Output contracts: versioned bounded acceptance report.
- State ownership: test harness owns a single run record.
- Resource ownership: export transaction owns file/stream and closes deterministically.
- Error behavior: a failed measurement is marked incomplete, never interpreted as feature support.
- Universality rules: records evidence; adds no compatibility dispatch.
- Performance constraints: recording overhead quantified and disabled outside requested runs.
- Tests required: schema, bounds, incomplete run, aggregation median/p90/p95.
- CI requirements: schema/unit tests; CI wording cannot claim hardware support.
- Hardware acceptance: execute the full `HARDWARE_ACCEPTANCE.md` matrix on representative devices.

## CAMX-113 — Generation-safe preview surface lease integration

- Tier: A for provider/owner lifecycle; B for the UI-to-provider adapter.
- Goal: connect the stable `SurfaceView` identity stream to revocable, generation-bound leases.
- Allowed files: `ui/components/StableSurfaceView.kt`, `core/camera/preview/**`, owner integration tests,
  and narrowly scoped `session/**` integration authorized by this ticket.
- Forbidden files: topology/canonicalization, cache, RAW, native control plane, OTA/signing.
- Input contracts: `PreviewSurfaceBinding` availability and identity-only invalidation events.
- Output contracts: one current `PreviewSurfaceLease`; old invalidations never revoke a newer lease.
- State ownership: provider owns identity reconciliation; session owner owns only its acquired lease.
- Resource ownership: `SurfaceView` owns the platform surface; lease is a revocable non-owning handle.
- Error behavior: invalid/stale/destructed surfaces yield `SurfaceUnavailable`, never a stale reopen.
- Universality rules: no device, display, resolution, or route assumptions.
- Performance constraints: one stable view; no view recreation or camera restart on recomposition.
- Tests required: repeated changed callbacks, destroy/recreate, stale destroy, disposal, rapid pause/resume.
- CI requirements: UI Camera2 guard and session owner/state suites remain green.
- Hardware acceptance: rotation/background/foreground/navigation stress with first-frame timing and leaks.
