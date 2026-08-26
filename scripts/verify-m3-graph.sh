#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly prod="app/src/main/java/com/sahidcode404/camx/core/imaging/graph"
readonly tests="app/src/test/java/com/sahidcode404/camx/core/imaging/graph"
readonly implementation_doc="docs/computational-raw/M3_TYPED_DAG_COMPILER.md"
readonly adr="docs/adr/ADR-035-deterministic-typed-graph-reference-semantics.md"

for path in \
  "$prod/GraphModel.kt" \
  "$prod/ReferenceNodes.kt" \
  "$prod/GraphCompiler.kt" \
  "$tests/M3GraphTestFixtures.kt" \
  "$tests/GraphCompilerTest.kt" \
  "$tests/GraphCompilerDeterminismTest.kt" \
  "$tests/ReferenceGraphExecutorTest.kt" \
  "$implementation_doc" \
  "$adr"; do
  test -f "$path" || { echo "M3 artifact missing: $path" >&2; exit 1; }
done

for requirement in \
  'class GraphValueType' \
  'class GraphNodeInvocation' \
  'object GraphCompiler' \
  'class ResourceProof' \
  'class DeterminismPlan' \
  'class M3ProcessingManifestPlan' \
  'class GraphSourceBinding' \
  'class SourceLineage' \
  'SCALAR_REFERENCE' \
  'reference.exact-copy' \
  'reference.exact-fork' \
  'reference.calibration-gate' \
  'NO_RANDOMNESS_ALLOWED_IN_M3_REFERENCE_PLAN' \
  'MAX_PLAN_RESERVED_BYTES' \
  'GraphCompileFailureReason.CYCLE' \
  'GraphCompileFailureReason.RESOURCE_BUDGET' \
  'releaseAfter' \
  'ReferenceGraphExecutor'; do
  if ! rg --fixed-strings --quiet "$requirement" "$prod"; then
    echo "M3 contract requirement missing: $requirement" >&2
    exit 1
  fi
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|acquireLatestImage|core\.rawvideo|feature\.camera|java\.nio\.file|androidx\.compose' \
  --glob '*.kt' "$prod"; then
  echo 'M3 graph package crossed a forbidden camera/raw-video/UI/filesystem boundary.' >&2
  exit 1
fi

for test_requirement in \
  'declarationOrderDoesNotChangeCompiledPlan' \
  'deterministicHundredPermutationCorpusProducesOnePlan' \
  'cycleIsRejectedBeforeExecution' \
  'typeMismatchIsRejected' \
  'resourceProofRejectsOverBudgetPlan' \
  'resourceProofAccountsForLivenessAndSafetyMargin' \
  'malformedNodeMetadataIsRejected' \
  'insufficientCalibrationIsRejected' \
  'duplicateProducerIsRejected' \
  'referenceExecutionIsBitExactAndRepeatable' \
  'sourceDigestMismatchFailsClosed' \
  'returnedOutputBytesAreDefensiveCopies'; do
  if ! rg --fixed-strings --quiet "$test_requirement" "$tests"; then
    echo "M3 acceptance test missing: $test_requirement" >&2
    exit 1
  fi
done

rg --fixed-strings --quiet 'Status: Accepted for the M3 software reference baseline' "$adr"
rg --fixed-strings --quiet 'No bounded resource proof means no executable plan.' "$adr"
rg --fixed-strings --quiet 'later scientific algorithms and optimized-provider qualification remain separate evidence gates' "$implementation_doc"
rg --fixed-strings --quiet '**M4 bounded burst acquisition** is the next sequential scientific milestone' "$implementation_doc"

echo 'M3 deterministic typed DAG/compiler verification passed.'
