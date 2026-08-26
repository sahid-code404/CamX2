# ADR-020: Typed graph and resource compiler

Status: Accepted

## Context

A processing graph that carries only buffers cannot prove representation legality, calibration compatibility, temporal causality, or bounded resource use.

## Decision

The graph IR is immutable and typed across representation, shape/strides/valid area, photometric/color/calibration domain, capture/temporal identity, uncertainty, memory domain/lifetime, precision, and versions. Nodes declare exact contracts, mutation semantics, halos/windows, workspace, latency, providers, and fallbacks.

Compilation performs legality and worst-case resource feasibility before capture. No bounded valid plan means admission fails.

## Consequences

OOM probing, illegal RAW relabeling, unbounded temporal state, and unqualified provider substitution are rejected before execution.
