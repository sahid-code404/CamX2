# ADR-015: Representation and product type system

Status: Accepted

## Context

A string format label cannot safely encode sensor-domain versus processed provenance, opaque transport, output pixel semantics, or legal conversions.

## Decision

Acquisition is classified into typed `InterpretableSensorDomain`, `CameraProcessed`, or `OpaqueTransport` representations. Outputs are distinct `SensorNegative`, `ComputationalNegative<FusedCfaRadiance | LinearSceneRgb>`, and `ProcessedSourceMaster` products.

Constructors/serializers must reject illegal representation transitions. Opaque transport cannot enter reconstruction. Processed provenance survives conversions.

## Consequences

Representation fraud becomes a type/validation failure rather than a UI convention. Runtime serialization remains a final defensive validation layer.
