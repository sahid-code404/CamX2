# CamX

CamX is a universal Android camera platform under an architecture-first rebuild. This checkpoint
establishes ownership, state, cache, topology, preview, RAW, native-memory, and debug-OTA contracts;
it intentionally does not claim Phase 2 photography completeness or hardware support.

The permanent development package is `com.sahidcode404.camx`, so CamX and the reference CameX app
can be installed together for hardware A/B testing.

## Verify

```bash
./scripts/verify-architecture.sh
./gradlew testDebugUnitTest lintDevOta assembleDevOta
```

Start with [the architecture constitution](docs/ARCHITECTURE_CONSTITUTION.md), then use
[the implementation backlog](docs/IMPLEMENTATION_BACKLOG.md) for bounded follow-up work.
