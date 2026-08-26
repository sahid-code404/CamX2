# ADR-009: Permanent fixed-signer debug OTA

Status: Accepted

## Context

Frequent hardware testing needs installable debuggable builds with update continuity, separate from
stable release signing and from CameX.

## Decision

`devOta` uses package `com.sahidcode404.camx`, remains debuggable, and is signed by a distinct
permanent CamX development certificate. CI publishes `CamX-dev.apk` plus a bound manifest to rolling
release `dev-latest`. Client verifies package, forward version, SHA-256, and signer before invoking
the visible Android installer.

## Consequences

The development private key is intentionally repository-visible and must not secure production. It
must never be regenerated. OTA work starts only after first frame or explicit user action.
