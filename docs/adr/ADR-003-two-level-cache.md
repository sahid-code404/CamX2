# ADR-003: Two-level camera cache

Status: Accepted

## Context

A complete topology is valuable for diagnostics and routing but unnecessarily large for the first
verified preview.

## Decision

Persist a tiny independently validated hot-start snapshot and a separate full topology cache. A valid
hot route can open before full cache decode/reconciliation; both are environment/schema bound.

## Consequences

There are two codecs and atomic transactions, but startup does bounded work and cache corruption has
a narrow failure boundary. Session code receives memory snapshots and never performs cache I/O.
