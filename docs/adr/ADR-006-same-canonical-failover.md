# ADR-006: Fail over only within one canonical lens

Status: Accepted

## Context

One optical lens may be exposed through several routes. A route can fail structurally, but silently
switching optics violates user intent.

## Decision

Only structural profile/session/RAW failure may attempt another profile whose parent canonical
fingerprint exactly matches the selected lens. Storage, timeout, cancellation, and transient
availability failures cannot rotate profiles.

## Consequences

Topology must preserve parent identity and failure classification must be exhaustive. If all sibling
profiles fail, CamX reports error rather than selecting a different optical lens.
