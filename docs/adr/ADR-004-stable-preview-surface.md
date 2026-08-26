# ADR-004: Stable SurfaceView preview

Status: Accepted

## Context

Recreating preview views during recomposition, resume, or switching causes stale Surface generations
and extra compositor/session churn.

## Decision

Start with one remembered `SurfaceView` owned by the Android view tree. Compose draws overlays only.
Every configuration recomputes geometry. TextureView is added only after a measured need and full
lifecycle/transform tests.

## Consequences

Preview pixels bypass Compose and favor low compositor overhead. Transform flexibility is lower, so
crop/rotation policy and device testing are mandatory.
