# CP3 Android heap-admission recovery

Real-device evidence showed a successful CP1 8/8 burst and CP2 8/8 calibration followed by `CP3 resident-memory admission failed` before fusion allocation.

The failure was a false-negative admission decision. CP3 previously derived its budget from `Runtime.totalMemory() - Runtime.freeMemory()` immediately after the RAW burst. On ART, `Runtime.freeMemory()` describes currently committed free heap; it is not a reliable measure of reclaimable heap capacity. Transient preflight, burst, evidence, and callback allocations may already be unreachable but not yet collected, so the budget can be smaller than the capacity ART can actually provide under allocation pressure.

CP3 now admits the already-resident immutable RAW FrameSet plus its bounded fused raster against `Runtime.maxMemory()` with an explicit 48 MiB application reserve, capped by the frozen 1 GiB CP3 bound. The low-memory CP3 v2 output remains 4 bytes per active pixel and CP4 continues to stream it without making a second full-resolution copy.

The hardware gate remains fail-closed: this change does not reduce the eight-frame burst, fabricate calibration, silently fall back to a single frame, or claim CP3/CP4 success without real fusion and DNG save evidence.
