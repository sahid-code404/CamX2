# Startup Architecture

## Valid-cache path

```text
process/activity create
  -> install one stable SurfaceView
  -> Android permission result + surface-ready gates
  -> in-memory SettingsSnapshot + tiny HotStartSnapshot
  -> validate cached environment and verified route
  -> CameraSessionController.open
  -> configure preview-only PRIVATE session with resolved FPS
  -> first capture result
  -> first valid preview frame
  -> release post-first-frame work
```

The two gates may arrive in either order. No modal app dialog, topology decode, full discovery,
network, update check, diagnostics formatting, deep AUX scan, RAW output, or Vulkan initialization is
on this path.

## First-install path

With no valid hot snapshot, CAMX-102 performs one metadata-only seed batch over the public IDs returned
by `CameraManager.cameraIdList`. The batch accepts at most 64 advertised IDs, de-duplicates them, and
reads `getCameraCharacteristics` at most once for each unique ID. An oversized advertised set fails
closed before characteristics reads rather than creating unbounded startup work.

For each accessible public ID the seed adapter reads only lens facing, at most 16 valid focal lengths,
sensor physical size, the backward-compatible request capability, and whether the public stream map
advertises at least one `SurfaceHolder` output. It does not retain output sizes, enumerate other stream
formats, inspect RAW/high-resolution/high-speed capabilities, walk physical-camera relationships, run
native discovery, canonicalize lenses, or format diagnostics. The resulting `CameraMetadataEvidence`
therefore keeps full capability lists empty instead of pretending that a deliberately partial seed
probe is a complete inventory.

A route is credible for seeding only when a public `SurfaceHolder` preview output is advertised.
Among credible candidates selection is deterministic: BACK, FRONT, EXTERNAL, then unknown facing;
then explicitly advertised backward compatibility; then completeness of focal/physical optical
evidence. Genuinely indistinguishable candidates use a SHA-256-derived opaque-ID ordering solely as a
non-semantic total-order tie-break. Camera-ID numeric or lexical value never assigns a role. The seed
route uses `JAVA_PUBLIC` provenance, `CameraTrust.ADVERTISED`, and otherwise unverified trust.

Every characteristics read is isolated. An inaccessible or malformed ID is recorded as typed seed
failure and does not erase successful evidence from other IDs. Missing optional facing/optics is not a
failure. Enumeration failure, all-inaccessible input, cancellation, or no preview-credible candidate
returns recoverable empty seed state without inventing a route. The coroutine owns only this local
sequential batch and checks cancellation between IDs.

CAMX-102 does **not** open the selected route and provides no visible preview. Camera open/configure/
repeat ownership begins in CAMX-103. Complete Java/physical/native AUX discovery and canonical
reconciliation remain post-frame CAMX-107 work.

## Post-frame gate

The session owner records `FIRST_PREVIEW_FRAME` from a generation-valid callback. The runtime opens a
one-shot gate that may start topology reconciliation, noncritical cache enrichment, diagnostics, and
an in-process lifecycle OTA check. Backgrounding cancels these jobs. Resume does not create a service
and does not repeat completed work unless policy says it is stale.

Performance is evaluated as distributions on identical hardware; no universal fixed-millisecond
promise is made.
