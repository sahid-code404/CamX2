# Performance Budgets and Tracing

CamX makes regression commitments, not universal millisecond promises. Measurements use monotonic
timestamps and distributions on identical hardware/build conditions.

## Trace milestones

`PROCESS_START`, `ACTIVITY_CREATE`, `SURFACE_READY`, `HOT_CACHE_READY`, `OPEN_REQUESTED`,
`CAMERA_OPENED`, `SESSION_CONFIG_REQUESTED`, `SESSION_CONFIGURED`, `FIRST_CAPTURE_RESULT`,
`FIRST_PREVIEW_FRAME`, `PREVIEW_STABLE`, `LENS_SWITCH_REQUEST`, `LENS_SWITCH_NEW_FIRST_FRAME`,
`SHUTTER_PRESS`, `RAW_SESSION_READY`, `RAW_REQUEST`, `RAW_RESULT`, `RAW_IMAGE`, `RAW_PAIR`,
`DNG_WRITE_START`, `DNG_WRITE_END`, and `PREVIEW_RESTORED` are recorded as primitive event/time/
generation tuples in a bounded ring. Formatting is diagnostics-only.

## Regression rules

On the same device and statistically comparable runs:

- valid-cache CamX median and p90 time-to-first-valid-frame must not be slower than CameX;
- lens-switch median and p90 to the new first frame must not regress;
- steady preview must perform no more application allocations per frame;
- valid-cache startup must read fewer or equal CameraCharacteristics records;
- idle CamX must attach no output more expensive than the display path;
- no network/deep discovery/native processing may begin before `FIRST_PREVIEW_FRAME`;
- bounded trace/metrics work must remain constant-memory under a ten-minute preview.

p95 is reported only with enough samples to be meaningful. Warm/cold process, cache state, debug or
profile variant, thermal status, selected lens/profile, stream configuration, and FPS request are
recorded with every run. Debug OTA stays primary for user testing; an optional profile variant may
be used for accurate CPU/allocation profiling and never replaces it.

Optimization priority follows measured critical cost: surface availability, open dependencies,
characteristics reads, session configuration, cache parsing, ImageReader/session churn, copies, GC,
and JNI traffic. Moving code to C++ is not itself evidence of improvement.
