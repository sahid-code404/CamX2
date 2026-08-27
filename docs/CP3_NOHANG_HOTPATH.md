# CP3 no-hang hot-path fix

This integration pass keeps CP1 capture, CP2 calibration, CP3 sensor-domain fusion, and CP4 DNG output semantics intact while removing avoidable full-resolution JVM overhead.

The CP3 full-resolution fusion loop no longer allocates a per-sample object. Bounds-proven internal RAW16 reads use a hot-path accessor, accepted frame/alignment state is prepared once, and residual rejection avoids a per-measurement square root. The fused U16 raster is hashed in bounded chunks rather than through repeated per-pixel digest calls.

CP4 writes the fused U16 raster through a reusable row buffer and writes metadata padding in bounded chunks instead of issuing byte-at-a-time OutputStream calls.

This is a performance/ANR-risk fix only. It does not relax exact timestamp pairing, frame membership, CP2 binding, alignment acceptance, noise-profile requirements, computational-DNG provenance, or any hardware verification gate.
