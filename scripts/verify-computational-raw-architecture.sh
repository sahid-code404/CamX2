#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

required_docs=(
  docs/computational-raw/REVISION_2_FREEZE.md
  docs/computational-raw/CONTRACTS.md
  docs/computational-raw/MODULE_BOUNDARIES.md
  docs/computational-raw/PROTOTYPE_DECISION_REGISTRY.md
  docs/computational-raw/IMPLEMENTATION_ROADMAP.md
)

required_adrs=(
  docs/adr/ADR-014-sensor-truth-terminology.md
  docs/adr/ADR-015-representation-product-type-system.md
  docs/adr/ADR-016-generation-bound-acquisition-handoff.md
  docs/adr/ADR-017-one-canonical-lens-per-reconstruction.md
  docs/adr/ADR-018-shared-evidence-constrained-imaging-engine.md
  docs/adr/ADR-019-computational-negative-pixel-semantics.md
  docs/adr/ADR-020-typed-graph-and-resource-compiler.md
  docs/adr/ADR-021-reference-semantics-and-backend-separation.md
  docs/adr/ADR-022-measurement-visibility-noise-uncertainty.md
  docs/adr/ADR-023-bounded-admission-backpressure-cancellation.md
  docs/adr/ADR-024-failure-isolation-and-source-retention.md
  docs/adr/ADR-025-split-sensor-and-computational-dng.md
  docs/adr/ADR-026-raw-video-container-contract.md
  docs/adr/ADR-027-raw-video-codec-and-packed-none.md
  docs/adr/ADR-028-processing-manifest-and-exact-profile-certification.md
  docs/adr/ADR-029-ai-measurement-constrained-provider.md
  docs/adr/ADR-030-negative-production-stops-before-rendering.md
  docs/adr/ADR-031-bounded-in-process-v1-compute.md
)

for path in "${required_docs[@]}" "${required_adrs[@]}"; do
  test -f "$path" || { echo "Missing computational RAW architecture artifact: $path" >&2; exit 1; }
done

for path in "${required_adrs[@]}"; do
  grep -Fxq 'Status: Accepted' "$path" || {
    echo "Semantic ADR is not Accepted: $path" >&2
    exit 1
  }
done

freeze=docs/computational-raw/REVISION_2_FREEZE.md
contracts=docs/computational-raw/CONTRACTS.md
registry=docs/computational-raw/PROTOTYPE_DECISION_REGISTRY.md
roadmap=docs/computational-raw/IMPLEMENTATION_ROADMAP.md
constitution=docs/ARCHITECTURE_CONSTITUTION.md

# Bind the new architecture to the accepted working camera frontier.
grep -Fq '75f56063cd34f802fe1e404574b496412ba3955c' "$freeze"
grep -Fq 'CameraSessionController' "$freeze"
grep -Fq 'CameraSessionController' "$contracts"

# Freeze product/representation truth rather than file-format branding.
for token in InterpretableSensorDomain CameraProcessed OpaqueTransport SensorNegative ComputationalNegative ProcessedSourceMaster FusedCfaRadiance LinearSceneRgb; do
  grep -Fq "$token" "$contracts" || { echo "Missing frozen contract token: $token" >&2; exit 1; }
done

# Mandatory replaceable seams and admission-safe codec baseline.
for token in RawVideoContainerContract RawVideoCodecContract PACKED_NONE SensorDngWriter ComputationalDngWriter; do
  grep -Fq "$token" "$contracts" || { echo "Missing frozen boundary: $token" >&2; exit 1; }
done

# Implementation candidates must remain explicitly provisional in M0.
grep -Eq 'MCAP-based CXRB \| PROVISIONAL|MCAP-based CXRB.*PROVISIONAL' "$registry"
grep -Eq 'compressed RAW-video codec/default \| PROVISIONAL|compressed RAW-video codec/default.*PROVISIONAL' "$registry"
grep -Eq 'separate compute process \| PROVISIONAL|separate compute process.*PROVISIONAL' "$registry"

# Roadmap must keep implementation behind semantic freeze and real-source evidence.
for milestone in 'M0 —' 'M1 —' 'M2A —' 'M2B —' 'M3 —' 'M4 —' 'M5 —' 'M6 —' 'M7 —' 'M8A —' 'M8B —' 'M9 —' 'M10 —' 'M11 —' 'M12 —' 'M13 —' 'M14 —'; do
  grep -Fq "$milestone" "$roadmap" || { echo "Missing roadmap milestone: $milestone" >&2; exit 1; }
done

# The top-level constitution must also carry the computational RAW extension.
for token in EvidenceConstrainedImagingEngine PACKED_NONE 'processed-source' 'artistic rendering'; do
  grep -Fq "$token" "$constitution" || { echo "Architecture Constitution missing: $token" >&2; exit 1; }
done

echo 'Computational RAW Revision 2 architecture freeze verified.'
