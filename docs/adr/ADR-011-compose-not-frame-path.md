# ADR-011: Compose is not in the frame path

Status: Accepted

## Context

Emitting camera results or preview buffers into Compose creates allocations, recomposition churn, and
latency unrelated to display transport.

## Decision

Camera frames go directly from Camera2 private Surface to SurfaceView. Compose receives only a
low-frequency `CameraUiSnapshot`; metrics aggregate before publication.

## Consequences

Overlays remain modern/declarative while preview performance is isolated. Features needing pixels use
explicit bounded analysis outputs, never Compose rendering.
