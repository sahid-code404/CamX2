# M2A — RAW-video Container Prototype

Status: Reference CXRB software prototype implemented; technology selection and physical acceptance pending

## Implemented checkpoint

The M2A reference candidate consumes immutable M1 sensor-domain evidence encoded as the frozen `PACKED_NONE` baseline. It does not own Camera2, `ImageReader`, preview, capture requests, or topology. The implementation lives under `core/rawvideo/container` and is replaceable behind the frozen `RawVideoContainerContract`.

Implemented contracts:

- full uint64 `FrameOrdinal` representation using Kotlin `ULong` and bit-preserving 64-bit serialization;
- 64-bit file offsets/lengths and storage capability declaration;
- sequential append only;
- explicit segment, representation, and codec epochs;
- exact sensor, host, normalized timestamp and timebase uncertainty fields;
- exact M1 canonical representation descriptor bytes and immutable historical acquisition identity bytes;
- bounded canonical metadata;
- explicit gap/discontinuity records rather than silent frame loss;
- per-header CRC32, descriptor CRC32+SHA-256, identity CRC32+SHA-256, metadata CRC32, payload CRC32+SHA-256;
- segment-wide SHA-256 checkpoint binding exact start/end offsets, ordinal range, and record/frame/gap counts;
- `FileDescriptor.sync()` before a segment is considered durable;
- normal-close rollback of an open uncheckpointed segment;
- bounded sequential recovery with a fixed 64 KiB payload buffer and no unbounded frame index;
- in-place truncation to the previous verified checkpoint after a damaged tail;
- parser limits for segments, records, frame payload, descriptor, identity, metadata, and total scan bytes;
- deterministic mutation/truncation fuzz tests;
- API-23-safe Java file primitives only (`RandomAccessFile`, `FileDescriptor.sync`), with no `java.nio.file` dependency.

## Current candidate status

CXRB is **not selected for shipping**. MCAP is **not rejected**. This checkpoint creates the deterministic sequential reference needed for evidence collection and later comparison. The M0 prototype registry remains PROVISIONAL.

## Remaining M2A acceptance evidence

Before M2A can be marked complete, physical-device evidence must demonstrate:

1. sustained sequential write margin on representative internal/UFS and slower supported storage classes through thermal plateau;
2. bounded queue and memory high-water behavior when fed at target RAW-video rates;
3. actual large-file behavior beyond 2 GiB/4 GiB where the target filesystem/storage supports it;
4. forced process-kill/power-loss recovery at randomized write positions with bounded corruption radius;
5. corruption injection across header, descriptor, identity, metadata, payload, and checkpoint layers;
6. API-23 device execution of the required path;
7. comparison against an MCAP adapter or a documented evidence-based reason to reject that candidate;
8. measured p50/p95/p99 append/checkpoint latency and sustained MB/s with device/storage/thermal fingerprints.

No physical support, sustained RAW-video support, or final container selection is claimed by a green host CI build.
