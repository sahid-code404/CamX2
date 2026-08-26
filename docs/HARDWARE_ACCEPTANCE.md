# Hardware Acceptance

CI validates software invariants; only recorded physical-device runs establish hardware behavior.
Each report identifies build SHA/APK hash/signer, API/build fingerprint, cache state, thermal state,
canonical lens/profile fingerprints (opaque IDs redacted if shared), and trace distribution.

For every visible canonical lens/profile combination, exercise:

| Area | Required cases | Acceptance |
|---|---|---|
| Launch | first install, cold valid cache, warm valid cache, invalid cache | system permission is direct; credible first preview; no startup modal/network/deep scan before frame 1 |
| Lifecycle | pause/resume ×20, screen off/on, rotate, Activity recreate | current surface/session only; counters stabilize; no old callback mutates new state |
| Switching | slow and rapid switch ×50, front/rear, portrait/landscape | selected optical lens never changes except by user intent; first-frame traces recorded |
| Contention | another camera app then CamX, CamX then another app, service restart where testable | typed recoverable error and clean recovery; no permanent rejection from contention |
| FPS | override off; supported fixed/variable requests; unusual inputs | no FPS key when off; first request uses advertised resolved range when on; actual FPS distribution reported |
| Viewfinder | high resolution off/on across orientations | supported cadence and correct crop/rotation/mirror; no hardcoded display assumptions |
| RAW | every RAW-trusted profile, rotate at shutter then rotate during save, cancel/timeout/storage full | exact pair, valid DNG orientation, no orphan/pending row, preview-only session restored |
| Memory | switches, RAW ×20, lifecycle ×20, contention cycles | device/session/image/native/thread counters return to quiescent band; no monotonic growth |
| OTA | install dev build, update forward, corrupt APK, wrong signer/package, cancel download | forward update retains data/signer; invalid artifacts rejected; installer is user-visible |

Compare valid-cache startup and lens-switch median/p90 to the CameX checkpoint on the same hardware.
A failure record includes trace and resource snapshots; it must not be converted into a universal
device quirk until generic paths and scope tests satisfy the constitution.
