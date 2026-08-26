# ADR-027: RAW-video codec contract and mandatory PACKED_NONE

Status: Accepted

## Context

Compression ratio is scene-dependent, while capture admission must remain safe even on incompressible RAW.

## Decision

CamX freezes `RawVideoCodecContract` and mandatory reversible `PACKED_NONE`. Admission reserves the uncompressed canonical raster plus framing/durability margin. Every compressed codec is provisional and must be bit-exact, bounded, independently decodable, allocation-safe, versioned, and removable.

Codec transforms cannot change sample precision, representation, CFA, calibration, dimensions, or uncertainty.

## Consequences

JPEG-LS, predictor/Rice, Zstd, LZ4, or another codec can win M2B only on end-to-end bytes/energy/thermal/sustainability evidence. Shipping no compressed codec remains valid.
