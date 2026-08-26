# ADR-032: CamX2 install identity is independent from CamX

Status: Accepted

## Context

CamX2 is intentionally built from the accepted CamX implementation baseline, but it must be installable on the same Android device as the existing CamX APK. Android installation identity is controlled by the Gradle `applicationId`; the Kotlin/Android namespace does not need to change to achieve side-by-side installation.

A full source-package rename would touch camera, topology, RAW, OTA, tests, JNI-facing names, and architecture guards without changing the Android package-manager identity requirement.

## Decision

CamX2 uses Android application ID `com.sahidcode404.camx2`.

The source namespace and Kotlin package hierarchy remain `com.sahidcode404.camx` for now. This is intentional compatibility-preserving internal naming and is not the install identity.

The development OTA trust contract, packaged-manifest verification, CI publisher checks, and fixed update endpoints are bound to `com.sahidcode404.camx2` and the `sahid-code404/CamX2` rolling release. The existing permanent development signer is retained so CamX2 OTA continuity remains stable across future CamX2 builds.

## Consequences

- CamX and CamX2 can be installed simultaneously and have separate app data, permissions, package-manager state, FileProvider authority, and update identity.
- A CamX APK cannot be accepted as a CamX2 OTA update because the package identity differs.
- A CamX2 APK cannot replace the installed CamX package.
- Camera behavior, Camera2 ownership, AUX topology, preview, RAW capture, computational-imaging architecture, and source packages are otherwise unchanged.
- Any future source namespace rename is a separate refactor and must not be confused with Android install identity.
