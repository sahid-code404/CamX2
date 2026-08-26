# AI Modification Policy

Architecture authority is based on change surface, not confidence claims.

## Tier A — architecture model required

May change `core/camera/session/**`, `runtime/**`, `topology/**`, `cache/**`, `raw/**`, native public
headers/ownership/JNI, update verification/signing, state machines, threading, CI/workflows,
build/toolchain identity, application/API floor, native capability/linkage policy, OTA packaging, and
`scripts/verify-*`.
Every change must name the invariant, ownership transfer, stale behavior, failure classification,
unit tests, CI guard impact, and hardware acceptance step. Cross-boundary changes require an ADR.

## Tier B — contract implementation

May implement preview stream/size policy, FPS resolver, settings persistence, diagnostics formatting,
performance UI, integration tests, and other non-ownership-heavy features. Tier B consumes immutable
contracts and cannot add a Camera2 owner, alter canonical identity, change JNI ownership, or loosen a
guard. It also cannot change the API-23 floor, optional-native availability, requested/baseline
classification, callback-permit admission, or cleanup authority. If a contract is insufficient, stop
and open a Tier-A migration ticket.

## Tier C — bounded presentation/support

May implement Compose rows/screens, components, strings, icons/resource wiring, documentation, pure
unit tests, formatting, and isolated fixes outside protected paths. Tier C must not edit Tier-A paths
unless a Tier-A ticket explicitly lists the exact files and review checks. “Documentation” here means
bounded explanatory or presentation text; the constitution, ADRs, ownership/state/error/threading/
native/CI contracts, AI policy, and implementation backlog are Tier-A architecture.

## Protected paths

```text
app/src/main/java/com/sahidcode404/camx/core/camera/session/**
app/src/main/java/com/sahidcode404/camx/core/camera/runtime/**
app/src/main/java/com/sahidcode404/camx/core/camera/topology/**
app/src/main/java/com/sahidcode404/camx/core/camera/cache/**
app/src/main/java/com/sahidcode404/camx/core/camera/raw/**
app/src/main/java/com/sahidcode404/camx/core/camera/diagnostics/NativeCapabilities.kt
app/src/main/java/com/sahidcode404/camx/core/camera/diagnostics/NativeCore.kt
app/src/main/java/com/sahidcode404/camx/core/update/**
native/core/**
scripts/verify-*.sh
tools/dev-signing/**
.github/workflows/**
.github/CODEOWNERS
.github/pull_request_template.md
app/build.gradle.kts
build.gradle.kts
settings.gradle.kts
gradle/libs.versions.toml
gradle/wrapper/**
scripts/install-android-sdk.sh
scripts/package-dev-ota.sh
scripts/verify-packaged-ota.sh
docs/ARCHITECTURE_CONSTITUTION.md
docs/ARCHITECTURE_PLAN.md
docs/CAMERA_STATE_MACHINE.md
docs/THREADING_MODEL.md
docs/ERROR_MODEL.md
docs/RESOURCE_OWNERSHIP.md
docs/NATIVE_MEMORY_MODEL.md
docs/CI_ARCHITECTURE.md
docs/TESTING_STRATEGY.md
docs/AI_TASK_POLICY.md
docs/IMPLEMENTATION_BACKLOG.md
docs/adr/**
```

Review rejects files outside a ticket's allowlist, undocumented new mutable state, an owner without a
close path, unbounded work, identity strings replacing value types, or tests weakened to accept a
regression. It also rejects API-floor drift, unconditional post-23/private/vendor native dependencies,
device-API-as-capability assumptions, suspension inside an authoritative camera mutation, callback
admission by generation equality alone, repeated requested-configuration fallback, and cleanup that
can publish after its permit becomes stale.
