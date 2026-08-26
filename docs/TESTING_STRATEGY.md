# Testing Strategy

## Pure JVM tests

Test value validation, topology permutation determinism and conservative separation, state transition
legality, typed failure policy, selection/session/capture generation rejection, FPS resolution,
geometry, bounded actual-FPS metrics, trace overflow, RAW pairing/closure, DNG orientation,
MediaStore rollback, cache codecs, settings memory-first behavior, and OTA rejection matrices.

CAMX-100A additionally tests that requested-configuration rejection is nonstructural and enables only
one `REQUESTED -> SAFE_BASELINE` attempt, while safe-baseline rejection alone changes preview trust and
permits same-canonical failover. Transition tests require preserved optical/profile/route/selection
identity plus a strictly newer session generation. Pure asynchronous-ownership tests permute A/B/C
callback order, surface replacement, pause, shutdown, requested/baseline replacement, stale cleanup,
duplicate first frames, and duplicate resource delivery; only the exact current permit may publish or
adopt, and every stale delivery closes exactly once.

Native-capability policy tests cover API 22 rejection, the API-23 Java Camera2 baseline, API-24
Camera/media floors, the API-26 hardware-buffer floor, unimplemented `Unsupported`, missing public
library, missing required symbol, and full `Available`. Device API alone must never produce
availability.

## Native host and Android ABI tests

Host C++ compiles with C++20, `-Wall -Wextra -Werror -pedantic` and exercises bounded timestamp,
trace, counters, buffer-pool ownership, and API-neutral owner move/release/reset/exactly-once deletion.
Follow-up CI adds ASan/UBSan and fuzz targets for binary metadata. Gradle compiles JNI for
arm/arm64/x86/x86_64. Post-assembly validation inspects every packaged shared object for ABI parity,
API-23 build notes, public dependency closure, strong undefined-symbol availability with versions,
and approved exports. Presence of four `libcamx_core.so` filenames alone is not an ABI/API proof.

## Instrumentation and lifecycle

Before an API-23 launch claim, GitHub instrumentation installs the freshly verified v1+v2-signed APK
on an API-23 image, starts the Activity, exercises permission UI, loads `libcamx_core.so`, and checks
schema, runtime API, compiled API 23, pointer width, and counter count. A modern-API smoke proves that
the same baseline remains loadable while optional native capabilities remain typed `Unsupported`
unless actually implemented and probed.

Future Camera2 instrumentation also permutes same-route stale opens, surface replacement, latest-wins
switching, pause, cancellation, RAW restore, and MediaStore failures. Compose tests verify
permission/error actions, accessibility, and stable AndroidView identity. Macrobenchmark/profile
builds measure startup and switching but never replace debug OTA or API-floor smoke evidence.

## Physical hardware

Only `HARDWARE_ACCEPTANCE.md` establishes actual support. CI language is restricted to build/policy/
ownership correctness; emulator language is restricted to that virtual image. Neither can claim lens,
RAW, orientation, FPS, thermal, lifecycle-soak, or leak compatibility on physical hardware.
