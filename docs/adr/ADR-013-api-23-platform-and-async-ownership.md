# ADR-013: API-23 platform baseline and single-use asynchronous ownership

Status: Accepted

## Context

CamX declared a universal architecture but the foundation application and native core were built with
an Android API-29 floor. Lowering only the Gradle `minSdk` exposed independent contracts: Camera NDK
and media-image ownership begin at API 24, hardware-buffer ownership begins at API 26, an ELF can
retain later loader/symbol requirements even when the merged manifest says 23, and Android 6 requires
v1 APK signing.

The state model also treated all preview configuration rejection too coarsely. Rejection of optional
requested FPS/stream/enhancement choices is not proof that the profile cannot preview, while rejection
of a deliberately conservative baseline is structural evidence. Separately, equality of route and
generation values does not make a callback unique: duplicate or replaced callbacks can carry equal
values, delivered resources need one disposition, and old cleanup can finish after newer intent.

These platform, failure, and asynchronous-ownership decisions cross build, JNI, session, OTA, CI, and
evidence boundaries, so they require one Tier-A migration rather than local suppressions.

## Decision

### Exact application and native floor

Android API 23 is the exact CamX application baseline. The Gradle model, every variant, merged APK,
development manifest, and publisher must agree. The development APK has one permanent signer and both
v1 and v2 signatures.

`libcamx_core.so` is compiled for API 23 in all four required ABIs and contains only the load-safe JNI,
bounded containers, counters, traces, buffer pool, and API-neutral RAII foundation. It cannot have an
unconditional dependency on a post-23, private, or vendor platform library. CI checks every packaged
shared object’s ABI, Android build note, dependencies, strong undefined symbols with versions, and
approved exports against the API-23 public NDK surface.

Kotlin/Java Camera2 remains the only device/session control plane. CAMX-100A ships no optional native
backend or dynamic loader. Future public-native capabilities have these independent floors:

| Capability | Public library | Minimum API |
|---|---|---:|
| Camera NDK metadata evidence | `libcamera2ndk.so` | 24 |
| Media image/reader ownership | `libmediandk.so` | 24 |
| Hardware-buffer ownership | `libnativewindow.so` | 26 |

Availability is typed as `Available`, `Unsupported`, `UnavailableBecauseApiLevel`,
`UnavailableBecauseLibrary`, or `UnavailableBecauseSymbol`. A future implementation reports
`Available` only after meeting the minimum API and resolving the complete required public symbol set.
API level alone is never capability.

### Requested configuration fallback

A preview attempt is explicitly `REQUESTED` or `SAFE_BASELINE`. Rejection of `REQUESTED` options is
nonstructural, changes no persistent trust, and permits exactly one new baseline attempt. The
transition preserves canonical lens, profile, route, and selection generation while strictly
advancing session generation and replacing the pending configuration permit.

Only rejection of an owner-issued, exact-permit-valid `SAFE_BASELINE` attempt is structural. It
rejects preview trust for that profile and may invoke failover to another profile of the same
canonical lens. Baseline attempts cannot repeat, reverse to requested, or be labeled by a callback.

### Non-suspending mutation and single-use permits

`CameraSessionController` remains the sole state and Camera2 resource owner.
`CameraStateMutationGate` serializes authoritative mutations on the long-lived camera dispatcher. Its
adapter may suspend to dispatch/acquire serialization, but the mutation block cannot suspend, wait,
join, perform I/O, or call potentially re-entrant resource cleanup.

Before an asynchronous command, the owner publishes immutable operation intent and creates a
single-consumption permit bound to owner identity, operation sequence, stage, active selection and
generations, surface identity, preview-attempt kind, and capture token where applicable. A callback
must consume that exact permit before publishing state or adopting its delivered resource. Generation
or route equality alone does not admit it. Duplicate, replaced, paused, or shutdown callbacks are
stale.

Each callback-delivered resource is resolved once: current admission adopts it; stale admission closes
it once. Switch, pause, and shutdown invalidate permits and detach owned resources inside the mutation
before close calls run outside it. Cleanup attempts every detached close, retains multiple failures,
and re-enters through a one-shot cleanup permit so stale completion cannot publish over newer intent.

### Evidence boundary

Static CI establishes source/model policy, compilation, unit tests, produced APK signing/identity, and
all-ABI ELF compatibility for one SHA. An API-23 emulator can separately establish install, Activity
start, and actual baseline JNI load for its image. Only recorded physical-device acceptance can
establish camera-route, lens, FPS, RAW, lifecycle, thermal, or leak behavior.

## Consequences

- API-23 Java Camera2 remains usable when every optional native capability is unavailable or
  unsupported.
- Future native metadata/image/hardware-buffer work requires a separate Tier-A ticket, public API
  probes, an API-23 fallback, all-ABI load tests, and RAII/leak evidence.
- Optional requested preview settings cannot poison profile trust or create an unbounded retry loop.
- Callback identity and resource disposition become one-shot ownership facts rather than timing or
  generation assumptions.
- Cleanup is slightly more explicit because invalidation, detached close, and completion publication
  are separate phases; this is required to prevent callback re-entry and stale completion races.
- CAMX-100A adds no Camera2 open/configure feature implementation, optional native backend, RAW
  behavior, device quirk, OTA network client, or physical-hardware support claim.

## Rejected alternatives

- Lowering only the manifest or suppressing API lint: this can publish an APK with a later-floor ELF or
  runtime call.
- Linking Camera NDK into the baseline and checking `SDK_INT` before use: loader-time dependencies are
  resolved before that branch.
- Treating every configuration rejection as structural: optional user requests are not profile proof.
- Retrying the requested configuration generically: it permits loops and repeats the rejected input.
- Accepting callbacks by equal generations: duplicates and replaced operations can still pass.
- Closing current owner slots from stale callbacks or publishing before cleanup completes: both allow
  old work to damage newer intent.

This ADR extends ADR-001 (single owner), ADR-002 (Camera2 control plane), ADR-008 (coarse JNI), and
ADR-009 (development OTA) without transferring their authority.
