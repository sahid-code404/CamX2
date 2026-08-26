#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly docs=(
  docs/ARCHITECTURE_CONSTITUTION.md
  docs/ARCHITECTURE_PLAN.md
  docs/CAMEX_MIGRATION_AUDIT.md
  docs/CAMERA_STATE_MACHINE.md
  docs/THREADING_MODEL.md
  docs/CACHE_ARCHITECTURE.md
  docs/STARTUP_ARCHITECTURE.md
  docs/ERROR_MODEL.md
  docs/NATIVE_MEMORY_MODEL.md
  docs/PERFORMANCE_BUDGETS.md
  docs/RESOURCE_OWNERSHIP.md
  docs/HARDWARE_ACCEPTANCE.md
  docs/PREVIEW_ARCHITECTURE.md
  docs/RAW_ARCHITECTURE.md
  docs/OTA_ARCHITECTURE.md
  docs/UI_ARCHITECTURE.md
  docs/TESTING_STRATEGY.md
  docs/CI_ARCHITECTURE.md
  docs/AI_TASK_POLICY.md
  docs/IMPLEMENTATION_BACKLOG.md
)
for document in "${docs[@]}"; do
  test -s "$document" || { echo "Architecture document missing: $document" >&2; exit 1; }
done

adr_count="$(find docs/adr -maxdepth 1 -type f -name 'ADR-*.md' | wc -l)"
test "$adr_count" -ge 12 || { echo 'At least twelve foundation ADRs are required.' >&2; exit 1; }

for type in CameraRoute CameraRouteId CameraCapabilities CameraStreamCapability CameraFpsCapability \
  CameraMetadataEvidence CameraEnvironmentFingerprint CanonicalLens CanonicalLensFingerprint \
  CameraProfile CameraProfileFingerprint CameraTrust PreviewTrust RawTrust ActiveCameraSelection \
  SelectionGeneration SessionGeneration CaptureToken PreviewConfiguration PreviewStreamType \
  PreviewConfigurationAttemptKind PreviewFpsRequest PreviewFpsResolution HotStartSnapshot \
  CameraTopologySnapshot RawCaptureContext RawPair CameraFailure RequestedConfigurationKind \
  CameraStartupTrace CameraResourceSnapshot CameraUiSnapshot CameraStateMutationGate \
  CameraAsyncOwnership PendingCameraOperationPermit CameraResourceCleanup CameraCleanupPlan; do
  rg --quiet "(?:class|interface|enum) ${type}\b" app/src/main/java || {
    echo "Core architecture type missing: $type" >&2
    exit 1
  }
done

readonly ci_workflow=".github/workflows/ci.yml"
for requirement in ':app:verifyApi23Baseline' './scripts/verify-native-api23.sh'; do
  rg --fixed-strings --quiet "$requirement" "$ci_workflow" || {
    echo "CAMX-100A CI requirement missing: $requirement" >&2
    exit 1
  }
done

./scripts/verify-universality.sh
./scripts/verify-camera-owner.sh
./scripts/verify-no-services.sh
./scripts/verify-hot-paths.sh
./scripts/verify-native-boundaries.sh
./scripts/verify-resource-ownership.sh
./scripts/verify-cache-boundaries.sh
./scripts/verify-discovery-purity.sh
./scripts/verify-preview-policy.sh
bash ./scripts/verify-visible-preview.sh
bash ./scripts/verify-topology-reconciliation.sh
bash ./scripts/verify-lens-test-ui.sh
./scripts/verify-development-ota.sh

echo 'Architecture verification passed.'
