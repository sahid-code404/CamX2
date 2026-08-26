# ADR-026: RAW-video container contract before container selection

Status: Accepted

## Context

A RAW-video master needs exact representation/timebase/provenance/integrity/recovery semantics that must survive replacement of a byte-container implementation.

## Decision

CamX freezes `RawVideoContainerContract`: product tracks and IDs, uint64 frame ordinals, exact representation descriptors, timestamps/timebase uncertainty, bounded public interpretation metadata, hashes/provenance/uncertainty, epochs, sequential bounded append, layered integrity, independently recoverable segments, 64-bit offsets, bounded parsers/recovery, explicit gaps, and storage capability declarations.

MCAP-based CXRB is only an M2A candidate.

## Consequences

A candidate container may be selected or rejected on mobile durability/performance evidence without reopening capture, codec, or reconstruction architecture.
