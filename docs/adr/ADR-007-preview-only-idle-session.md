# ADR-007: Preview-only idle session

Status: Accepted

## Context

Permanent RAW/JPEG/YUV outputs increase memory, HAL bandwidth, configuration constraints, and session
startup cost even when the user never captures.

## Decision

Idle photo mode attaches only the private display output. RAW is created for one bounded shutter
transaction, destroyed, and followed by explicit preview-only restoration.

## Consequences

Shutter incurs session reconfiguration on devices that require it, but live preview remains cheap and
universal. Future optimizations need measurements and cannot make RAW permanent by default.
