# ADR-009: Permanent fixed-signer debug OTA

Status: Accepted, amended by ADR-032

## Context

Frequent hardware testing needs installable debuggable builds with update continuity. CamX2 must also
remain independently installable from the previous CamX application on the same Android device.

## Decision

`devOta` uses Android application ID `com.sahidcode404.camx2`, remains debuggable, and is signed by
the permanent development certificate. The migrated Kotlin/Android source namespace remains
`com.sahidcode404.camx`; it is not the Android package-manager identity. CI publishes `CamX-dev.apk`
plus a bound manifest to the CamX2 repository rolling release `dev-latest`. Client verification binds
package, forward version, SHA-256, and signer before invoking the visible Android installer.

## Consequences

CamX2 can coexist with the previous CamX package `com.sahidcode404.camx`, and neither APK can replace
the other because their application IDs differ. The development private key is intentionally
repository-visible and must not secure production. It must never be regenerated. OTA work starts only
after first frame or explicit user action.
