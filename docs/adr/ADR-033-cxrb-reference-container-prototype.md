# ADR-033: Provisional CXRB reference container prototype

Status: Provisional — M2A software checkpoint; physical durability/throughput evidence pending

## Context

Revision 2 freezes `RawVideoContainerContract` independently of any concrete byte container. M2A must prototype a candidate against that seam using M1 canonical source evidence and the mandatory reversible `PACKED_NONE` codec baseline. The candidate must be replaceable without changing camera ownership, source truth, product semantics, or graph semantics.

## Decision

Implement a narrow CXRB reference candidate in-process with only Java/Kotlin API-23-safe file primitives. The reference writer is sequential, uses 64-bit file offsets, records full uint64 frame ordinals, preserves explicit sensor/host/normalized timestamps and mapping uncertainty, stores exact immutable representation and capture-identity bytes, records explicit gaps/discontinuities, and accepts only M1 `InterpretableSensorDomain` + `PACKED_NONE` packets.

Durability is segment-scoped. A segment becomes durable only after a checkpoint footer containing record/frame/gap counts, byte offsets, ordinal range, and SHA-256 of the entire segment is written and `FileDescriptor.sync()` completes. Individual frame bodies have bounded lengths plus CRC32/SHA-256 integrity. Normal cancellation truncates any open uncheckpointed segment to the prior durable boundary. Recovery scans sequentially with bounded buffers and bounded record/segment counts; corruption or truncation discards at most the current uncommitted/corrupt segment and reports the previous durable checkpoint.

The implementation does not select CXRB as the shipping container and does not reject MCAP. It establishes a deterministic reference candidate for the M2A comparison. The `MCAP-based CXRB` registry entry therefore remains explicitly **PROVISIONAL** until real-device storage, power-cut, corruption-radius, large-file, and sustained thermal/storage evidence is collected.

## Consequences

- `CameraSessionController` remains the sole camera owner; the container package imports no Camera2/ImageReader APIs.
- Container semantics cannot relabel processed or opaque evidence as sensor RAW.
- `PACKED_NONE` remains the only codec accepted by this M2A reference writer; compressed codecs remain M2B work.
- Segment/record parsing allocates only bounded headers, descriptor/identity/metadata blocks, and a fixed 64 KiB payload/recovery buffer; payloads are streamed during recovery.
- 64-bit file-size declarations above 2 GiB are represented and tested without allocating multi-gigabyte memory.
- A final technology decision is deferred until the physical evidence gates in the M2A roadmap are satisfied.

## Rollback

Delete the `core/rawvideo/container` candidate, its tests, M2A guard, and this provisional ADR. M1 acquisition evidence and all frozen Revision-2 semantics remain unchanged.
