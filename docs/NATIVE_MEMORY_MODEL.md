# Native Memory and JNI Model

Native code is used only when it removes measured allocation, copy, parsing, or processing overhead.
It does not bypass CameraService/HAL and does not own Android lifecycle or ordinary Camera2 control.
Kotlin/Java Camera2 is the sole camera control plane on every supported Android version.

## API-23 loadable baseline

`libcamx_core.so` is compiled with `ANDROID_PLATFORM=android-23` for `armeabi-v7a`, `arm64-v8a`,
`x86`, and `x86_64`. CMake rejects another platform floor. The baseline contains coarse JNI health,
bounded containers, counters, traces, buffer-pool code, and API-neutral RAII only. It has no
unconditional `DT_NEEDED` dependency on Camera NDK, media-image, hardware-buffer, private system, or
vendor libraries. Raising only the APK manifest while leaving a later-floor ELF, or lowering only the
manifest around one, violates the constitution.

## Ownership rules

- Native ownership is RAII only. Owning bare pointers, owning `void*`, and manual multi-exit cleanup
  are forbidden.
- `UniqueNdkOwner` is an API-neutral, non-copyable ownership mechanism. CAMX-100A instantiates no
  Camera NDK, `AImage`, `AImageReader`, or `AHardwareBuffer` owner in the baseline.
- `NativeBufferPool`, `BoundedTimestampIndex`, work queues, and `NativeTraceBuffer` require capacity
  at construction. Overflow has an explicit drop/reject action.
- JNI global references require a named owner and debug counter. No native object retains an
  Activity/View/Surface longer than its documented lease.
- Large buffers are returned or released on pause; diagnostic snapshots contain counts, not buffers.
- Destructors do not call Kotlin, block on unbounded work, or hide failures.

## Optional public-native capabilities

| Capability | Public library | Minimum API | CAMX-100A status |
|---|---|---:|---|
| Camera NDK metadata evidence | `libcamera2ndk.so` | 24 | `Unsupported`; no backend or loader ships |
| Media image/reader ownership | `libmediandk.so` | 24 | `Unsupported`; no backend or loader ships |
| Hardware-buffer ownership | `libnativewindow.so` | 26 | `Unsupported`; no backend or loader ships |

`NativeCapabilityAvailability` distinguishes `Available`, `Unsupported`,
`UnavailableBecauseApiLevel`, `UnavailableBecauseLibrary`, and `UnavailableBecauseSymbol`. API level
is checked first, but meeting it is not availability. An optional backend must be explicitly
implemented and must resolve its complete required public-library symbol set before reporting
`Available`. Partial resolution fails closed. Missing optional capability never disables the API-23
application or moves Camera2 control out of Kotlin.

Adding a loader or optional native target is a later Tier-A migration with its own public-library
allowlist, all-ABI load tests, RAII/leak tests, and API-23 fallback. Private/vendor libraries and
Camera NDK device/session control remain forbidden.

## Coarse JNI boundary

JNI accepts or returns validated batches: compact metadata evidence, trace snapshots, resource
counters, or future frame-set handles with explicit close. There is no call per pixel, frame field,
or metadata key. Kotlin validates sizes before entry; native validates again before allocation.
Exceptions are translated at the boundary and never cross native worker threads.

The foundation schema-2 JNI call returns runtime API, compiled API, pointer width, and the six bounded
resource counters. Consumers must reject a wrong schema, wrong exact length, compiled API other than
23, invalid pointer width, or malformed counters. Camera NDK metadata parsing and RAW ownership are
later Tier-A tickets gated by capability probes and benchmarks.

CI inspects every packaged shared object in every ABI, not only `libcamx_core.so`. It proves the
Android build note, ELF class/machine, dependency closure against API-23 public NDK stubs, strong
undefined-symbol availability with symbol versions preserved, ABI library-set parity, and the core
JNI export allowlist. Missing notes, private/vendor dependencies, later-only relocation formats, and
unapproved weak imports fail closed. This static proof does not replace an API-23 native-load smoke.

## Future processing dispatch

`RawFrame -> RawFrameSet.takeFrames() -> ProcessingGraph -> ImageProcessor` is the stable one-time
ownership-transfer boundary; owning pair/frame-set wrappers are intentionally non-copyable.
Scalar code is the reference. Optional ARM64 NEON is selected by runtime CPU feature detection, not
vendor identity, and must pass numerical equivalence tests. Vulkan is lazy, post-frame,
capability-detected, failure-isolated, cached, and always has a CPU fallback.

## Leak acceptance

Debug counters cover native images, hardware buffers, allocated buffer bytes, worker count, queue
depth, and JNI global references. After repeated switch/capture/pause/resume cycles, counts must
return to the same quiescent band. Sanitizer/host tests cover bounded structures; hardware soak tests
cover any future public-NDK owners. Zero image/hardware-buffer counts in CAMX-100A mean those optional
backends are not implemented, not that their ownership has been hardware-validated.
