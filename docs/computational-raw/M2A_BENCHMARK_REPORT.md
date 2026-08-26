# M2A Container Benchmark Report

Status: Software correctness baseline recorded; physical performance section intentionally pending

## Reference candidate

Candidate: provisional CXRB sequential reference writer/recovery implementation.
Codec input: frozen `PACKED_NONE` only.
Source basis: immutable M1 canonical sensor rasters.

## Software evidence collected in CI

The automated suite exercises checkpointed multi-segment round trip, explicit sequence gaps, normal cancellation rollback, truncation recovery, corruption-radius recovery, wrong-digest rejection before write, in-segment identity-change rejection, declared file-size admission failure before partial frame write, full uint64 terminal frame ordinal, >2 GiB storage-limit header round trip without large allocation, and deterministic randomized mutation/truncation fuzzing.

These tests are correctness and boundedness evidence. They are not storage-performance evidence.

## Physical benchmark matrix — required before selection

| Metric | Required evidence | Current result |
| --- | --- | --- |
| sustained write MB/s | representative real devices/storage through thermal plateau | PENDING |
| append latency p50/p95/p99 | device/storage fingerprinted | PENDING |
| checkpoint/fsync p50/p95/p99 | device/storage fingerprinted | PENDING |
| memory/queue high-water | target RAW-video rate | PENDING |
| >2 GiB and >4 GiB file behavior | supported target filesystems | PENDING |
| randomized kill/power-loss recovery | real storage, repeated trials | PENDING |
| corruption radius | randomized structural/payload corruption | host fuzz present; physical PENDING |
| API-23 runtime | physical API-23 device | PENDING |
| MCAP comparison | same corpus/device/storage | PENDING |

## Decision

No shipping container is selected by this report. CXRB remains PROVISIONAL and MCAP remains an open candidate. A future update to ADR-033 may accept or reject a candidate only after the pending evidence is attached.
