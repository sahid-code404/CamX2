# ADR-014: Sensor truth terminology

Status: Accepted

## Context

Public Android APIs expose interpretable RAW representations but cannot prove that a sensor/HAL performed no prior sensor-domain correction. Processed YUV/P010 and opaque private transport also exist.

## Decision

CamX uses four truthful terms: sensor-domain source/product, computational sensor-sourced product, processed-source product, and opaque transport. The strongest Sensor-mode claim is public interpretable sensor-domain samples with no CamX sample-changing processing.

P010/YUV can never be labeled Sensor RAW or Computational RAW. `RAW_PRIVATE` is never guessed into a universal RAW format.

## Consequences

UI, diagnostics, manifests, filenames/metadata, certification, and fallbacks disclose actual source/product truth. Marketing wording cannot override representation provenance.
