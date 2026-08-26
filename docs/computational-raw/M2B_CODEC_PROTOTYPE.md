# M2B — RAW-Video Codec Prototype

Status: Reference compressed-codec software checkpoint implemented; physical acceptance and shipping selection pending

M2B implements the frozen `RawVideoCodecContract` behind an isolated, replaceable package while preserving the working CamX2 preview/AUX/topology/one-shot RAW paths and sole `CameraSessionController` ownership.

## Implemented contract surface

Production package:

`app/src/main/java/com/sahidcode404/camx/core/rawvideo/codec/`

Implemented types and adapters:

- `CanonicalCodecFrame` — immutable canonical sensor-raster input whose byte count, descriptor canonical extent, and M1 SHA-256 must agree.
- `RawVideoCodecDescriptor` — family/version/pretransform/static parameters plus mandatory independent-frame-decode declaration.
- `CodecReservation` — pre-allocation decoded/output/workspace bounds with checked arithmetic.
- `EncodedFrameHeader` — codec identity, exact decoded/encoded lengths, exact encoded bit count, CRC32, encoded SHA-256, decoded-raster SHA-256, representation-descriptor SHA-256, and bounded frame parameters.
- `EncodedFrameLease` / `DecodedFrameLease` — one-time move ownership for encoded and decoded payloads.
- `RawVideoCodec` — isolated admission/encode/decode seam with no Camera2 or container dependency.
- `PackedNoneCodec` — mandatory frozen reversible baseline; no sample transform and no compression.
- `RiceDeltaByteCodec` — provisional compressed reference candidate using modulo-256 byte delta plus per-frame Rice coding.

## Boundedness and truth rules

`PACKED_NONE` reserves exactly the canonical raster byte count.

`RICE_DELTA_BYTE` chooses `k` from the finite set `0..7` using one fixed 256-bin histogram. The `k=7` option proves a maximum of 9 encoded bits per decoded byte. At the M1 maximum canonical raster size of 512 MiB, the candidate's precomputed maximum encoded payload is 603,979,776 bytes. No encode path grows a dynamic output buffer.

Every decode validates codec identity, representation descriptor digest, decoded extent, encoded extent, encoded bit extent, CRC32, encoded SHA-256, codec parameters, and final decoded canonical-raster SHA-256 before returning a decoded lease. A malformed frame cannot return partial output.

The codec package accepts only `InterpretableSensorDomain` canonical rasters. It does not infer RAW truth, decode `RAW_PRIVATE`, change sample precision, query current UI/topology state, or create/open any camera resource.

## Software acceptance coverage

Unit coverage includes:

- exact `PACKED_NONE` round trip;
- immutable source-byte freezing;
- move-once encoded ownership;
- corruption rejection;
- exact Rice-delta round trip on structured and deterministic-random rasters;
- independent second-frame decode with no prior-frame state;
- hard 9-bit/source-byte expansion reservation;
- invalid Rice parameter rejection;
- structurally truncated bitstream rejection;
- decoded-raster digest mismatch rejection;
- maximum-M1-size reservation arithmetic without large allocation;
- deterministic 200-case two-codec round-trip corpus;
- deterministic 200-case encoded-payload mutation rejection.

## Tier-A review fields

**Invariant:** `PACKED_NONE` remains the mandatory reversible admission-safe baseline. A compressed candidate cannot redefine source/product truth.

**Ownership transfer:** codec inputs are immutable; encoded and decoded payloads cross the seam through move-only leases. Camera ownership is unchanged.

**Stale behavior:** codec frames contain immutable representation/digest truth only; no live camera generation or UI lookup exists here. Stale acquisition evidence must already have been rejected by M1 before codec admission.

**Failure classification:** malformed codec input, digest mismatch, parameter mismatch, representation mismatch, or bound violation fail in the codec domain only and cannot damage camera/profile trust.

**Unit tests:** focused contract/codec tests plus deterministic corpus and mutation fuzz tests are part of `testDebugUnitTest`.

**CI guard impact:** `scripts/verify-m2b-codec.sh` enforces required artifacts, `PACKED_NONE`, bounded Rice expansion, independent decode, and absence of Camera2/container coupling.

**Hardware acceptance step:** sustained encode/decode throughput, storage margin, energy, thermals, API-23 real-device behavior, and competing-codec comparison remain mandatory before any shipping selection.

## Explicitly deferred

M2B does not implement continuous Camera2 RAW-video acquisition (M10), does not wire the candidate into CXRB as a shipping path, does not select a compressed default, and does not claim physical RAW-video support.

The next dependency-ordered scientific milestone remains M3 deterministic typed DAG/compiler. M10 still requires M2A + M2B + M4.
