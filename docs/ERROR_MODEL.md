# Error Model

Every `CameraFailure` has a category, structural flag, trust effect, one-shot configuration-fallback
permission, generic retry permission, same-canonical failover permission, and user-action requirement.
Error text is presentation data and never drives policy.

| Failure | Category | Structural? | Trust effect | Config fallback | Generic retry | Same-canonical failover | User action |
|---|---|---:|---|---:|---:|---:|---|
| `PermissionDenied` | permission | no | none | no | no | no | settings only if permanently denied |
| `CameraInUse` / `MaximumCamerasInUse` | availability | no | temporarily unavailable | no | bounded | no | optional |
| `CameraDisabled` | availability | no | temporarily unavailable | no | no | no | device/admin policy |
| `CameraDisconnected` | device | no | temporarily unavailable | no | bounded | no | none |
| `CameraDeviceError` | device | explicit classification | temporary or reject preview profile | no | only if nonstructural | only if structural | optional |
| `OpenTimeout` | device | no | temporarily unavailable | no | bounded | no | none |
| `SurfaceUnavailable` | surface | no | none | no | when surface returns | no | none |
| `RequestedConfigurationRejected(kind)` | preview/session | no | none | exactly one safe-baseline attempt | no | no | none |
| `SafeBaselineConfigurationRejected` | session | yes | reject preview profile | no | no | yes | none |
| `PreviewTimeout` | preview | no | temporarily unavailable | no | bounded | no | none |
| `RawUnsupported` / `RawSessionRejected` | RAW | yes | reject RAW profile only | no | no | yes | none |
| `RawCaptureTimeout` | RAW | no | none | no | bounded | no | none |
| `RawPairTimeout` | RAW | no | none | no | bounded | no | none |
| `DngWriteFailure` | storage | no | none | no | no | no | retry/save diagnostics |
| `MediaStoreFailure` | storage | no | none | no | no | no | free storage/permission |
| `StaleSelection` / `StaleSession` / `StaleCapture` | concurrency | no | none | no | no | no | none |
| `Cancelled` | control | no | none | no | no | no | none |

Classification is centralized and exhaustive. A transient error is never persisted as permanent
rejection. An output error is never converted to a camera-route error. Retry budgets are bounded and
reset only by explicit success or lifecycle policy; they are not loops hidden inside callbacks.

## Requested configuration versus safe baseline

`RequestedConfigurationRejected` covers optional FPS, exact range, high-resolution preview, optional
YUV/analysis/auxiliary streams, aspect preference, and enhancement requests. It is evidence only about
that requested combination. It never enters an error state, mutates `PreviewTrust`, enables generic
automatic retry, or enables profile failover. The owner may issue exactly one new
`SAFE_BASELINE` attempt with optional outputs and overrides removed, after strictly advancing the
session generation and replacing the configuration permit.

`SafeBaselineConfigurationRejected` can be created only for a consumed permit that the owner itself
issued as `SAFE_BASELINE`. It is structural profile evidence, enters `StructuralError`, rejects preview
trust for that profile, and may invoke same-canonical failover. A platform callback or caller cannot
relabel an arbitrary request as the baseline.

`CameraFailurePolicy` enforces that failover and permanent trust rejection imply structural failure,
structural failure cannot generically retry, and configuration fallback is nonstructural with no trust
change. These are construction invariants, not review conventions.
