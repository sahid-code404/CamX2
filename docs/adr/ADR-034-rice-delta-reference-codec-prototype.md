# ADR-034 — Provisional Rice-Delta RAW-Video Codec Reference

Status: Provisional — M2B software checkpoint; physical Pareto evidence pending

## Context

Revision 2 freezes `RawVideoCodecContract` and requires `PACKED_NONE` as the admission-safe reversible baseline. A compressed RAW-video codec may be accepted only if it is bit exact, independently frame-decodable, bounded before allocation, malformed-input safe, API-23 compatible on required paths, and materially improves sustained storage/energy/thermal behavior rather than merely reporting a good compression ratio.

M2B therefore needs an isolated compressed candidate that exercises the frozen seam without selecting a shipping codec prematurely.

## Decision

Implement `RICE_DELTA_BYTE` version 1 as a **reference candidate only**:

- each frame is independently encoded and decoded;
- the pretransform is modulo-256 forward byte delta;
- one bounded per-frame Rice parameter `k` in `0..7` is selected from a fixed 256-bin histogram;
- exact encoded bit count, encoded CRC32, encoded SHA-256, decoded canonical-raster SHA-256, and representation-descriptor SHA-256 are carried with the frame;
- a `k=7` proof gives a hard worst-case ceiling of 9 encoded bits per source byte;
- encode/decode allocations are preceded by explicit reservation checks;
- no Camera2, `ImageReader`, container implementation, filesystem, or UI dependency exists in the codec package.

`PACKED_NONE` remains frozen and mandatory. Admission must still reserve the viable `PACKED_NONE` path independently of whether `RICE_DELTA_BYTE` is attempted.

## Non-decision

This ADR does **not** select `RICE_DELTA_BYTE` as the shipping/default codec and does not reject JPEG-LS or other future candidates. Build success, unit tests, or desktop compression ratios are insufficient to make that choice.

## Acceptance gate

A compressed codec may move beyond provisional status only after representative-device evidence includes:

- bit-exact corpus round trip across supported public sensor representations;
- independent frame recovery and malformed/truncated/corrupt input rejection;
- API-23 physical runtime where required and ABI packaging coverage;
- bounded memory/workspace and output expansion under worst-case data;
- online encode/decode p50/p95/p99 throughput margin at target RAW-video rates;
- sustained storage reduction that remains useful after container/checkpoint overhead;
- energy and thermal benefit through plateau, not ratio alone;
- crash/corruption interaction with the selected M2A container;
- explicit comparison against shipping `PACKED_NONE` and any competing compressed candidate.

## Rollback

Delete the `RICE_DELTA_BYTE` adapter and its tests/benchmark records. The frozen codec seam, `PACKED_NONE`, M1 source evidence, M2A container prototype, camera ownership, and existing one-shot RAW behavior remain unchanged.
