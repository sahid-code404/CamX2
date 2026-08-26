# ADR-030: Negative production stops before artistic rendering

Status: Accepted

## Context

A scientific negative and a preferred aesthetic rendering have different truth and reproducibility requirements.

## Decision

The `EvidenceConstrainedImagingEngine` stops at sensor/computational/processed-source masters plus objective calibration/provenance. Creative tone curves, contrast, saturation, aesthetic sharpening, looks, and hidden rendering choices remain downstream and are never baked into Sensor-mode truth.

## Consequences

Users can render non-destructively while master evidence remains auditable and reusable by future processing versions.
