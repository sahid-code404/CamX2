# Camera State Machine

`CameraSessionController` is the sole state and Camera2 resource owner. Commands enter its serialized
camera-control context. Every authoritative mutation is a short non-suspending critical section on
the camera dispatcher; waits and cleanup execute outside it. Every transition emits a low-frequency
immutable snapshot; frame callbacks update bounded metrics, not state flow.

## States

| State | Resources that may be live | Meaning |
|---|---|---|
| `CLOSED` | none | Owner is shut down; only a new owner instance can restart it. |
| `WAITING_FOR_SURFACE` | stable surface reference may be absent | A route/lifecycle intent exists but no usable surface lease exists. |
| `OPENING` | open callback lease | One generation-bound device open is in flight. |
| `CONFIGURING_PREVIEW` | device + preview surface | A `REQUESTED` or `SAFE_BASELINE` preview-only attempt is being configured. |
| `PREVIEWING` | device + preview session + display surface | Repeating display request is active. |
| `SWITCHING` | old leases closing; new open callback may follow | Explicit selection changed; selection and session generations advanced. |
| `CONFIGURING_RAW` | device + temporary RAW output + display surface as supported | Bounded capture transaction is creating a non-idle session. |
| `CAPTURING_RAW` | RAW session + one request + transaction | Exactly one RAW request is outstanding. |
| `PAIRING_RAW` | bounded unmatched image/result maps | Exact sensor timestamps are being paired. |
| `WRITING_DNG` | matched image/result + pending MediaStore row | Camera route remains unchanged; I/O failure is nonstructural. |
| `RESTORING_PREVIEW` | device + display surface; RAW resources closing | Temporary outputs are destroyed and preview-only session is rebuilt. |
| `PAUSING` | leases closing | Lifecycle prevents new work; generations have already advanced. |
| `RECOVERABLE_ERROR` | ideally none; selected route retained | Retry/user/environment may resolve a transient failure. |
| `STRUCTURAL_ERROR` | none except stable UI surface | Active profile was structurally rejected; same-canonical policy may choose a sibling. |

## Transition contract

Intent and generations are invalidated under the mutation gate before detached resources are closed.
Cleanup runs outside the gate and publishes its destination only through its still-current one-shot
cleanup permit. A late Android callback closes only its delivered resource, exactly once, after it
fails to consume the exact callback permit.

| Caller / event | Allowed source | Destination | Create / destroy | Generation, cancellation, stale behavior | Failure class |
|---|---|---|---|---|---|
| Runtime `resume(route)` without surface | `WAITING_FOR_SURFACE`, `RECOVERABLE_ERROR` | `WAITING_FOR_SURFACE` | none | retain selection; cancellation has no effect | `SurfaceUnavailable` |
| Surface owner `attach` | `WAITING_FOR_SURFACE`, `RECOVERABLE_ERROR` | `OPENING` | create open callback lease | advance session; stale surface callback releases only its lease | open failures below |
| Runtime `open(route)` | `WAITING_FOR_SURFACE`, `RECOVERABLE_ERROR`, initial state | `OPENING` or `WAITING_FOR_SURFACE` | create open callback only when surface exists | explicit selection advances selection and session; cancellation advances session | permission/open failure |
| Camera `onOpened` | `OPENING` | `CONFIGURING_PREVIEW(REQUESTED)` | consume open permit, adopt device once, create requested preview config | require exact owner/stage/selection/session/surface permit; stale callback closes delivered device once | `StaleSession`, configuration errors |
| Requested configuration rejected | `CONFIGURING_PREVIEW(REQUESTED)` | `CONFIGURING_PREVIEW(SAFE_BASELINE)` | close rejected attempt; issue one conservative preview-only attempt | preserve canonical/profile/route/selection generation; strictly advance session generation and replace the permit | `RequestedConfigurationRejected`; no trust change |
| Safe baseline rejected | `CONFIGURING_PREVIEW(SAFE_BASELINE)` | `STRUCTURAL_ERROR` | close rejected attempt and detach profile resources | only the consumed baseline permit can reject preview trust; same-canonical failover may follow | `SafeBaselineConfigurationRejected` |
| Session `onConfigured` | either `CONFIGURING_PREVIEW` attempt | `PREVIEWING` | consume configuration permit, adopt session once, start first repeating request | stale or duplicate callback closes only its delivered session once | stale only |
| First valid frame | `PREVIEWING` | `PREVIEWING` | consume first-frame permit; mark trace/post-frame gate once | exact session/surface permit; stale or duplicate frames are no-ops | `PreviewTimeout` handled by watchdog |
| Runtime `switch(route)` | `PREVIEWING`, error, waiting, configuring, RAW, or restoring states | `SWITCHING` | cancel any capture/write transaction; close session/device | advance selection + session and invalidate capture token before cleanup; stale callbacks close their resources | close/open errors typed separately |
| Switch cleanup complete | `SWITCHING` | `OPENING` or `WAITING_FOR_SURFACE` | consume cleanup permit; create new open callback if surface ready | stale A/B cleanup cannot overwrite newer C intent; same command cannot silently select another canonical lens | open failures |
| Runtime `capture` | `PREVIEWING` | `CONFIGURING_RAW` | create capture token, context, bounded RAW output | capture cancellation restores preview; stale image closes immediately | `RawUnsupported`, session rejection |
| RAW session configured | `CONFIGURING_RAW` | `CAPTURING_RAW` | issue exactly one RAW request | token + selection + session must match | request rejection |
| RAW result/image partial | `CAPTURING_RAW`, `PAIRING_RAW` | `PAIRING_RAW` | insert into bounded timestamp index | overflow closes orphan image; timeout cancels transaction | `RawPairTimeout` |
| Exact pair | `PAIRING_RAW` | `WRITING_DNG` | transfer image ownership to writer transaction | token checked before transfer; later selection makes result stale and closes image | `StaleCapture` |
| Write success/failure | `WRITING_DNG` | `RESTORING_PREVIEW` | publish/delete row; close image; destroy RAW output | cancellation deletes pending row; never changes camera trust | DNG/MediaStore failure |
| Restore configured | `RESTORING_PREVIEW` | `PREVIEWING` | accept preview-only session | selection/session check; stale session closes | preview structural/transient failure |
| Lifecycle `pause` | every non-closed state | `PAUSING` | cancel transaction; close all Camera2/RAW resources | advance session and invalidate capture token before close; retain optical selection identity | close failures remain diagnostic |
| Pause cleanup | `PAUSING` | `WAITING_FOR_SURFACE` | consume cleanup permit; release camera-owned surface lease, not UI view | late callbacks can only close delivered resources; stale cleanup cannot publish | none |
| Owner `close` | every state | `CLOSED` | close all resources, dispatcher last | idempotent; advance session and invalidate capture token | none exposed to UI |
| Transient platform failure | active states | `RECOVERABLE_ERROR` | close invalid leases | trust becomes temporarily unavailable only | typed transient |
| Structural profile failure | open/config/capture/restore | `STRUCTURAL_ERROR` | close invalid leases | policy may retry sibling profile under same canonical fingerprint | typed structural |

## Generation rules

- `SelectionGeneration` changes only for explicit optical selection or invalidation.
- `SessionGeneration` changes before every device/session replacement and lifecycle close.
- `CaptureToken` is unique per shutter transaction and is invalidated before cancellation cleanup.
- Generation equality alone never accepts a callback. The callback must consume the exact one-shot
  `PendingCameraOperationPermit` created by the same owner for its stage and immutable operation
  identity: selection, session, surface, preview attempt, and capture token where applicable.
- The only preview-configuration self-edge is `REQUESTED -> SAFE_BASELINE`. It preserves canonical
  lens, profile, route, and selection generation, strictly advances session generation, and cannot
  repeat or reverse. A caller or callback cannot label an arbitrary attempt as the safe baseline.
- Stale callbacks never publish an error against current state and never close a resource fetched
  from current owner state; they close only the resource delivered to or created by that callback.
- A delivered resource is wrapped before callback admission. Permit consumption adopts it once;
  rejection or duplication stale-closes it once. Invalidation detaches current resources before close,
  and one close failure cannot prevent later detached resources from being closed and reported.
