# Resource Ownership

There is exactly one authoritative owner at any instant. A transfer is explicit in a method contract;
sharing a reference does not share close authority. Callback delivery is not ownership acceptance:
the exact one-shot operation permit must be consumed before a delivered resource can be adopted.

| Resource | Authoritative owner | Created | Destroyed | May transfer? | Stale callback action |
|---|---|---|---|---|---|
| `CameraDevice` | `CameraSessionController` | exact-permit-valid `onOpened` adoption | switch, pause, fatal session error, owner close | no | `CloseOnceCameraResource` detaches one cleanup under admission; close once after unlock |
| `CameraCaptureSession` | `CameraSessionController` | exact-permit-valid `onConfigured` adoption | reconfigure, switch, pause, owner close | no | `CloseOnceCameraResource` detaches one cleanup under admission; close once after unlock |
| Pending callback permit | `CameraAsyncOwnership` | authoritative intent is published before an async command | consumed once or invalidated by replacement/pause/shutdown | no | stale/duplicate permit cannot publish or adopt |
| Detached cleanup plan/permit | `CameraSessionController` | resources are detached after intent/generation invalidation | consumed once after all closes are attempted | no | stale cleanup completion cannot publish a destination |
| Stable preview View | Compose/Activity view tree | camera screen creation | Activity destruction | no | ignore callback for old view identity |
| Preview `Surface` lease | `PreviewSurfaceOwner` until attached; session lease while active | Surface callback | surface destroy or session cleanup | lease only | release stale lease, never current lease |
| RAW `ImageReader` | `RawCaptureTransaction` under session owner | `CONFIGURING_RAW` | transaction `finally` before preview restored | no | close reader and drain/close images |
| RAW `Image` | timestamp pairer, then transaction/writer | image callback acquire | orphan/overflow/stale/timeout or writer completion | once, pairer to writer | close immediately |
| Capture metadata/result | `RawCaptureTransaction` | capture callback | pair/write completion or timeout | immutable reference only | discard |
| Pending MediaStore row | `MediaStoreTransaction` | insert with `IS_PENDING=1` | publish on success; attempt delete and report cleanup failure | no | delete if transaction owns it; CAMX-108 recovers a surviving row |
| `AImage` (future optional) | API-24 Tier-A media-image module | only after API/library/symbol capability succeeds | optional owner destructor | explicit move only | unavailable/unsupported leaves Java ownership authoritative |
| `AHardwareBuffer` (future optional) | API-26 Tier-A hardware-buffer module | only after `libnativewindow.so` and symbol capability succeeds | optional owner destructor | move only unless an explicit acquire creates another reference | unavailable/unsupported creates no native owner |
| API-23 native core | `NativeCore` load boundary and native RAII types | process loads `libcamx_core.so` | process unload; bounded leases destruct normally | coarse JNI values/handles only | load failure is typed native unavailability, not a camera-route failure |
| Native buffer | `NativeBufferPool` lease | bounded pool checkout | lease return/pool shutdown | move-only lease | return lease |
| Topology snapshot | `CameraTopologyRepository` | pure resolver output | GC after atomic replacement/readers release | immutable sharing | do not publish stale reconciliation |
| Hot/full cache write | `CameraCacheRepository` | persistence request | atomic replace or temp cleanup | no | discard stale write before replace |
| Settings snapshot | `SettingsRepository` | update | GC after atomic replacement | immutable sharing | version-check persistence completion |
| OTA download `.part` | `UpdateRepository` transaction | user starts download | rename after verification or delete on failure/cancel | no | delete stale part |
| OTA state | `UpdateRepository` | post-frame/manual check | repository lifecycle | immutable sharing | do not publish stale request |
| Camera callback thread | `CameraSessionController` | owner construction | after permit invalidation and all detached Camera2 resources close | no | callbacks require exact permit; generation equality alone cannot admit them |
| Native workers | native processing runtime | lazy post-frame initialization | runtime shutdown/background memory trim | no | discard queued stale token |

Debug `CameraResourceSnapshot` reads counters owned by each boundary. It is diagnostic observation,
not ownership transfer. `CloseOnceCameraResource` resolves each callback delivery once as adopted or
stale-detached. The first stale resolution receives one `CameraResourceCleanup`; duplicate callbacks
and already-adopted deliveries receive no cleanup authority. Pause, switch, and shutdown invalidate
admission and detach current resources under the non-suspending mutation gate; close calls run outside
it. `CameraCleanupPlan` attempts every detached close once, retains later failures as suppressed detail,
and only the current cleanup permit may publish completion.
