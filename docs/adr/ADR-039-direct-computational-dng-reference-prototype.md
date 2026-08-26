# ADR-039: Narrow direct Computational DNG reference prototype

Status: Provisional M8B implementation decision; external interoperability evidence pending

## Context

The accepted architecture separates `SensorDngWriter` from `ComputationalDngWriter`. M7 now produces a real `FusedCfaRadiance` computational negative with its own output-derived radiance, uncertainty, provenance, graph identity, and calibration binding. Reusing Android `DngCreator` or stale Camera2 capture metadata for that product would violate metadata authority. The M8B roadmap therefore requires a replaceable experiment between a pinned/pruned DNG SDK, a narrow direct writer, and a standards-neutral computational master.

## Decision

CamX2 implements a narrow pure-Kotlin direct DNG writer as the **reference prototype** for the current M7 CFA product.

The reference writer:

1. accepts only immutable `FusedCfaRadiance` plus an explicit `ComputationalCfaDngAuthority` whose M5 calibration digest, CFA identity, and active area exactly match the M7 product;
2. requires explicit bounded `UniqueCameraModel` authority and accepted non-monochrome color calibration; missing metadata is a hard rejection rather than a fabricated fallback;
3. writes a deterministic little-endian classic-TIFF DNG 1.4-compatible Float32 CFA raster, with output black level zero and an output-derived white boundary;
4. re-phases the 2×2 CFA pattern to the exported M7 active-grid origin;
5. converts accepted M5 sensor-to-XYZ color matrices into the DNG XYZ-to-camera direction and encodes them as bounded SRATIONAL values while preserving the exact original calibration in private provenance;
6. carries the complete current M7 per-pixel uncertainty/support flags plus canonical provenance in bounded self-identifying `DNGPrivateData`;
7. uses checked layout arithmetic, bounded IFD/strip/private-data/file limits, and a streaming output path;
8. includes a narrow hostile-input inspector for files produced by this reference writer;
9. has no Camera2, Android `Image`, `ImageReader`, `DngCreator`, UI, topology, rendering, demosaic, remosaic, or AI dependency.

The current writer does not invent a DNG `NoiseProfile` from reconstructed variance. The current accepted M7 product is CFA, so no `LinearSceneRgb` path is created until a real linear computational product exists. RGB will not be remosaiced to satisfy a CFA file shape.

## Consequences

The direct writer is not yet declared the final shipping implementation. A pinned/pruned DNG SDK can replace it behind the frozen seam if named-decoder interoperability or security evidence is materially better. A standards-neutral linear/computational master also remains available where DNG cannot carry future product semantics safely.

M8B software CI proves deterministic construction, parser bounds, provenance/uncertainty preservation, and internal round trip only. It does not prove external decoder behavior or exact-profile physical quality. Named decoder validation and real-source calibration files remain mandatory before interoperability certification.

Failure of this writer or any external decoder never reclassifies the M7 computational negative, camera profile, or sensor support.

## License notice

This product includes DNG technology under license by Adobe.
