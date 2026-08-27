# CP1–CP4 hardware recovery gate

This note records the real-device failure observed after an 8-frame computational RAW transaction and the recovery requirements for the current integration branch.

Observed failures:

- CP3 could reject an otherwise valid `CP1 8/8 • CP2 8/8` burst with `CP3 resident-memory admission failed`.
- A capture immediately following a completed RAW burst could race preview restoration and fail before useful RAW evidence was produced (`CP1 RAW failed: 0/8 paired`).

Current production-path recovery:

- CP3 v2 retains only the fused signal raster at full resolution; diagnostic variance/contributor maps are no longer retained as full-resolution arrays.
- CP4 streams the immutable CP3 fused signal instead of allocating another full-resolution signal copy.
- CP1 burst admission uses the bounded VM heap capacity with an explicit reserve instead of transient `Runtime.freeMemory()` headroom.
- A computational RAW transaction does not complete until the restored preview has produced a verified frame, preventing the next shutter from entering the previous transaction's restoration window.

The feature must remain fail-closed. Do not claim computational RAW complete until a real-device run shows all of the following in one transaction: CP1 8/8, CP2 8/8, CP3 fused with at least two contributing frames and non-zero multi-frame pixels, CP4 DNG saved successfully, preview restored and verified, and a second consecutive computational RAW capture also succeeds.
