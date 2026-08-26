# M2B RAW-Video Codec Benchmark Report

Status: Software correctness baseline recorded; physical Pareto benchmark pending

## Candidates in this checkpoint

| Codec | State | Reversibility | Independent frame decode | Worst-case encoded payload |
| --- | --- | --- | --- | --- |
| `PACKED_NONE` v1 | FROZEN baseline | Bit exact | Yes | 1.000× decoded bytes |
| `RICE_DELTA_BYTE` v1 | PROVISIONAL reference candidate | Bit exact | Yes | <= 9 bits/source byte (1.125×) |

`RICE_DELTA_BYTE` is not selected as a shipping/default codec by this report.

## Deterministic software corpus

The checked-in test harness executes a bounded deterministic corpus rather than relying on timing-sensitive unit-test assertions:

- 200 source rasters spanning constant, ramp, block-correlated, and seeded pseudo-random byte patterns;
- every corpus raster is round-tripped through both `PACKED_NONE` and `RICE_DELTA_BYTE` (400 exact round trips);
- 200 independently encoded Rice-delta frames are bit-mutated and required to fail closed through layered integrity checks;
- focused tests cover truncation, parameter corruption, decoded-digest mismatch, independent second-frame decode, move-only ownership, and maximum-size reservation arithmetic.

This corpus establishes software reversibility/safety behavior only. It is not evidence of sustained mobile performance.

## Admission properties

`PACKED_NONE` requires no compression workspace and reserves exactly the decoded canonical-raster bytes.

`RICE_DELTA_BYTE` uses a fixed 256-entry histogram (2,048 bytes) during encode. The reference implementation computes output size before allocation and writes into one fixed output array. The decoder allocates the validated decoded extent only after header/codec/reservation checks. The candidate's `k=7` option bounds any frame to at most 9 encoded bits per source byte.

At the M1 canonical-raster ceiling of 512 MiB:

- decoded bytes: 536,870,912;
- maximum Rice-delta encoded payload: 603,979,776 bytes;
- encode histogram workspace: 2,048 bytes.

These are arithmetic bounds, not recommendations for admitting such a frame on a real device. The future graph/resource compiler must apply device-specific memory/storage margins.

## Physical benchmark matrix — pending

No compressed codec may be selected until representative devices/storage targets record at minimum:

| Evidence | Required measurement |
| --- | --- |
| Source cohorts | Real M1 canonical rasters stratified by exact lens/profile/format/mode/size/exposure regime |
| API baseline | Physical API-23 execution where required; all packaged ABIs verified |
| Encode latency | p50 / p95 / p99 per frame and sustained frames/s |
| Decode latency | p50 / p95 / p99 per independent frame |
| Online margin | Encode completion margin versus target sensor cadence |
| Storage | Sustained bytes/s before and after container/checkpoint overhead |
| Compression | median / p95 encoded ratio by source stratum, not global average only |
| Memory | peak Java/native working set and queue high-water under sustained load |
| Energy | joules/frame or battery power delta versus `PACKED_NONE` |
| Thermal | sustained behavior through thermal plateau and throttling transitions |
| Recovery | corruption/truncation interaction with selected M2A segment/checkpoint strategy |
| Competitors | At least `PACKED_NONE` and every serious compressed candidate on identical corpus/device conditions |

## Decision rule

Compression ratio alone cannot win. A compressed codec is acceptable only if it is bit exact, bounded, independently recoverable, API-compatible, and materially improves the end-to-end storage/energy/thermal sustainability frontier while retaining enough p99 encode margin for the intended RAW-video cadence.

Until that evidence exists, the only accepted baseline remains `PACKED_NONE`; `RICE_DELTA_BYTE` stays replaceable and provisional.
