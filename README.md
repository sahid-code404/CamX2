# CamX2

CamX2 is a universal Android camera platform built from the accepted working CamX baseline and extended under the computational RAW architecture. This checkpoint preserves camera ownership, state, cache, topology, preview, RAW, native-memory, and development-OTA contracts while the new imaging architecture is layered on top.

The permanent Android install identity is `com.sahidcode404.camx2`. It is intentionally different from the existing CamX package `com.sahidcode404.camx`, so CamX2 and CamX can be installed on the same device without replacing each other or sharing app data.

The Kotlin/Android source namespace remains `com.sahidcode404.camx` for compatibility with the migrated implementation. Namespace/source package and Android `applicationId` are deliberately separate concerns; only the latter controls package-manager install identity.

## Verify

```bash
./scripts/verify-architecture.sh
./gradlew testDebugUnitTest lintDevOta assembleDevOta
```

Start with [the architecture constitution](docs/ARCHITECTURE_CONSTITUTION.md), then use
[the implementation backlog](docs/IMPLEMENTATION_BACKLOG.md) for bounded follow-up work.
