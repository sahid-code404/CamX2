# ADR-001: One CameraDevice owner

Status: Accepted

## Context

Camera2 callbacks are asynchronous and can outlive selection, surface, and lifecycle intents. More
than one owner makes close authority and stale behavior ambiguous.

## Decision

Only `CameraSessionController` may open or own CameraDevice/session resources. Runtime sends typed
commands; discovery, UI, RAW, writers, and native metadata code cannot open a device. One serialized
dispatcher and generation model governs every operation.

## Consequences

Session code is Tier A and intentionally central. Tests can model all transitions and source guards
can reject extra owners. RAW and feature code require explicit leases instead of convenience opens.
