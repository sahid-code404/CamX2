# CameX Migration Audit

Reference: `sahid-code404/CameX` at
`71810628be26abb01a84dd1f544c9f9a9551a022`. Inspection was read-only. CamX preserves verified
behavioral lessons, not package structure or incidental implementation.

## Executive findings

CameX already protects several difficult truths: route identity differs from optical identity,
public and NDK metadata are evidence sources, cached topology can accelerate startup, profile
failover must remain within a canonical lens, RAW image/result ordering is nondeterministic, and
MediaStore publication must be transactional. Its Phase-2 implementation also accumulates several
responsibilities in large runtime/session classes, stores more topology than the minimal startup
path needs, uses broadly typed generations/keys, and reads display rotation during DNG writing
rather than treating shutter orientation as transaction identity. CamX keeps the proven behaviors
while making ownership and stale-callback rejection explicit before feature work resumes.

The exact checkpoint was also built independently during the audit: its 218 JVM tests, lint/debug
assembly, four-ABI native build, fixed-signer dev APK, and existing shell guards pass. That confirms
the checkpoint is a useful working oracle, but not that its guards cover the new constitution or that
CI proves hardware behavior.

## Subsystem decisions

| Subsystem | Decision | CameX protection / good behavior | CamX change, risk, and required tests |
|---|---|---|---|
| Camera discovery | REWRITE | Java advertised discovery is bounded; seed discovery avoids requiring a full probe. | Independent immutable `JAVA_PUBLIC`, `JAVA_PHYSICAL`, `NDK_ADVERTISED`, and later `NDK_DEEP` evidence. Risk: hiding AUX routes. Test backend purity, cancellation, incomplete/conflicting metadata, and first-install seeding. |
| NDK discovery | MOVE TO C++ | Public Camera NDK supplies independent advertised metadata without private vendor libraries. | Keep public metadata parsing and compact batched payloads native; no CameraDevice control. Test malformed/empty metadata, RAII counts, ABI builds, and JNI batch validation. |
| Topology repository | REWRITE | Atomic-ish topology publication and cache invalidation exist. | Publish one immutable `CameraTopologySnapshot` via `StateFlow`; resolver performs no I/O. Test readers never observe partial topology and invalid environments cannot publish cached routes. |
| Canonicalization | KEEP BEHAVIOR / REWRITE | Conservative optical matching uses multiple evidence families and collision guards. | Pure complete-evidence resolver; conflict or uncertainty preserves separate lenses. Never merge on focal length or resolution alone. Regression-test identical focal lengths, missing metadata, logical/physical aliases, and fingerprint collisions. |
| Profile model | KEEP BEHAVIOR / SIMPLIFY | Multiple route profiles can represent one optical lens and exact routing identity is retained. | Strongly type route/profile/canonical fingerprints and keep source provenance/trust independent. Test that route IDs cannot be mixed with fingerprints and every profile has exactly one parent lens. |
| Runtime coordinator | REWRITE | It sequences cache, discovery, selection, failover, session, and RAW. | Split orchestration policy from the sole resource owner; coordinator owns intent/order but never Camera2 objects. Risk: dual serialization. Test concurrent open/switch/pause/capture commands through one actor. |
| Session controller | REWRITE | One implementation owns Camera2 device/session and serializes operations. | Typed state machine, separate selection/session/capture generations, long-lived callback dispatcher, resource leases, and centralized stale close behavior. Test every transition, out-of-order callbacks, close idempotence, pause races, and resource counters. |
| Preview geometry | KEEP BEHAVIOR / REWRITE | Rotation/crop handling is isolated from feature UI. | Pure `PreviewGeometryCalculator` recomputes from current inputs with no prior-lens state. Test all rotations, facing/mirror rules, aspect ratios, and zero-size rejection. |
| Preview size selection | KEEP BEHAVIOR / SIMPLIFY | Capability-driven selection avoids fixed camera IDs. | Policy ranks only supported streams using view geometry, min frame duration, and FPS target. No fixed 1080p/16:9 threshold. Property-test deterministic output and cadence constraints. |
| FPS selection | KEEP BEHAVIOR / REWRITE | Active-profile advertised ranges are consulted. | Pure resolver returns requested, exact supported resolution or deterministic fallback reason before first repeating request. Test unusual, inverted, negative, sparse, fixed, and variable ranges. |
| Settings | REWRITE | DataStore persists user/lens preferences. | Memory-first immutable `SettingsSnapshot`; update memory synchronously and persist asynchronously. Test hot methods without storage access, crash-safe persistence, and rapid writes. |
| Cache | REWRITE | Environment-matched cache-first startup works and avoids repeated discovery. | Tiny hot snapshot separate from full diagnostic topology. Test schema/API/build/environment invalidation, corruption, atomic replace, and startup characteristics-read counts. |
| Trust | REWRITE | CameX records route/profile failures and distinguishes some structural failures. | Separate `MetadataTrust`, `PreviewTrust`, and `RawTrust`; transient states decay and storage never changes them. Test classification matrix and reboot/cache persistence rules. |
| RAW registry | DELETE | Global registry bridges active selection and capture state. | Transaction state belongs to session/runtime snapshot; no process-global mutable capture owner. Test a second transaction is rejected and cancellation clears state. |
| RAW transaction | REWRITE | Preview-only idle mode is restored around bounded RAW work and structural retry is same-canonical. | Explicit `RawCaptureTransaction` with captured generations, fingerprints, orientation, timeout, and lease ownership. Test every failure restores preview and no lens hopping occurs. |
| Timestamp pairing | KEEP BEHAVIOR / MOVE TO C++ LATER | Exact `SENSOR_TIMESTAMP` pairing is callback-order independent, bounded, and closes discarded images. | Preserve semantics behind a generic contract; native owner is optional only after measurement. Test both orders, duplicates, overflow, timeout, stale token, and exactly-once close. |
| DNG writer | REWRITE | Uses `DngCreator` and isolates write failures from camera routing. | Accept characteristics/result/image plus shutter-time orientation context; writer never queries display state. Test TIFF orientation mapping and write failure cleanup. |
| Orientation | REWRITE | Sensor/facing/display math exists. | Snapshot display rotation at shutter; carry it immutably and write TIFF orientation without rotating pixels. Test front/rear × four rotations and rotation change during save. |
| MediaStore | KEEP BEHAVIOR | `IS_PENDING=1`, write, publish, and delete-on-failure are transactional. | Preserve behind `MediaStoreTransaction`; surface cleanup failure now and add surviving-row recovery in CAMX-108. Test insert/open/write/publish/delete failures. |
| OTA | REWRITE | CameX has package/hash/signer checks, `.part`, manual installer, rolling debug release, and fixed signer. | Isolate camera startup with a first-frame gate; bind manifest to CamX package/signer and require explicit install UI. Test downgrade, package mismatch, digest mismatch, signer mismatch, cancellation, and atomic rename. |
| CI | KEEP BEHAVIOR / REWRITE | Architecture/OTA guards, unit tests, lint, APK assembly, signer/package checks already catch regressions. | Smaller portable guards mapped one-to-one to constitution rules, typed contract tests for semantic invariants, host C++ warnings-as-errors, and all ABI JNI builds. Static guards are used only where they can enforce an exact source boundary. |
| UI | REWRITE | Compose already stays above TextureView frame transport. | Stable `SurfaceView` through `AndroidView`; Compose only draws low-frequency overlays. Original CamX tokens/icon/splash. Test view identity across recomposition, nonmodal permission denial, and accessibility. |
| Diagnostics | SIMPLIFY | Compatibility export makes topology and failure behavior observable. | Primitive bounded traces/counters on hot paths; formatting on diagnostics request. Test overflow behavior, monotonic ordering, and no per-frame UI emissions. |

## Reference-specific hazards not carried forward

- `RawDngWriter` obtains current display rotation during write; CamX snapshots it at shutter.
- Session events/terminal callbacks carry a routing key but no selection/session generation. Reusing
  one route allows an old callback to affect or close a newer same-route attempt.
- Open/switch holds a mutex through open, configuration, and first frame, so rapid selections queue
  instead of becoming latest-wins intent.
- `CameraRuntimeCoordinator` combines startup, topology, selection, RAW, and state projection; CamX
  gives each state/resource an explicit owner without adding pass-through layers.
- Cache topology and startup concerns are coupled: full topology/trust JSON plus a DataStore
  `settings.first()` are read ahead of selection, and a valid cache suppresses post-frame advertised
  reconciliation. CamX uses a tiny hot snapshot and still reconciles later.
- String routing keys and raw `Long` epochs make accidental identity mixing easy; CamX uses value
  classes for IDs, fingerprints, and generations.
- A TextureView-based preview is not copied as an assumption; CamX begins with stable SurfaceView
  and treats backend changes as benchmarked architecture decisions.
- Global RAW registry state is not copied. Transactions remain bounded under the session owner.
- The exposed YUV option adds a drain-only ImageReader while users still see PRIVATE preview; it is
  not a real YUV viewfinder and is deleted/deferred.
- Existing architecture guards are large path/name-specific regular expressions. At least one
  `runBlocking {}` form escapes the claimed ban, and native checks cover only a named source file.
  CamX guards scan complete boundaries, while typed output plans and contract tests prove key
  reject/allow behavior.
- CameX development/stable publication can run without green validation for the same SHA. CamX
  publication is gated, and the CameX key is never reused.
- No license is present at the reference checkpoint. CamX independently reimplements behavior and
  does not transplant source.

## Hardware evidence to preserve

The reference implementation is evidence that advertised IDs, logical/physical relationships,
independent NDK metadata, conservative canonicalization, profile routing, preview verification,
exact RAW pairing, and transactional DNG publication are useful on real devices. It is not evidence
that any numeric ID, manufacturer branch, fixed resolution, or reported RAW capability is universally
usable. CamX therefore persists verified route/session evidence separately from advertised metadata.
