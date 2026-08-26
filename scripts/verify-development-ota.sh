#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly build_file="app/build.gradle.kts"
readonly workflow=".github/workflows/dev-ota.yml"
readonly expected_file="tools/dev-signing/EXPECTED_CERT_SHA256"
readonly encoded_signer="tools/dev-signing/camx-dev.jks.b64"
readonly verifier="app/src/main/java/com/sahidcode404/camx/core/update/verification/DevelopmentUpdateVerifier.kt"

for file in "$workflow" "$expected_file" "$encoded_signer" "$verifier"; do
  test -s "$file" || { echo "Development OTA file missing: $file" >&2; exit 1; }
done

for literal in \
  'create("devOta")' \
  'isDebuggable = true' \
  'applicationId = "com.sahidcode404.camx"' \
  'keyAlias = "camx-dev"'; do
  rg --fixed-strings --quiet "$literal" "$build_file" || {
    echo "Development OTA Gradle requirement missing: $literal" >&2
    exit 1
  }
done

if rg --fixed-strings --quiet 'applicationIdSuffix' "$build_file"; then
  echo 'Development OTA must not use an application ID suffix.' >&2
  exit 1
fi

for field in schema channel applicationId versionCode versionName minSdk apkAssetName sha256 \
  signingCertSha256 gitSha buildTimestamp changelog mandatory; do
  rg --quiet "val ${field}:" app/src/main/java/com/sahidcode404/camx/core/update/UpdateModels.kt || {
    echo "Development manifest field missing: $field" >&2
    exit 1
  }
done

expected="$(tr -d '[:space:]' < "$expected_file")"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo 'Pinned signer digest is malformed.' >&2; exit 1; }
mkdir -p build/verify-signer
base64 --decode "$encoded_signer" > build/verify-signer/camx-dev.jks
actual="$(keytool -exportcert -keystore build/verify-signer/camx-dev.jks \
  -storepass camx-dev-only-2026 -alias camx-dev 2>/dev/null | sha256sum | awk '{print $1}')"
test "$actual" = "$expected" || {
  echo "Permanent development signer mismatch: expected $expected, got $actual" >&2
  exit 1
}
rg --fixed-strings --quiet "const val CERT_SHA256 = \"$expected\"" "$verifier" || {
  echo 'Runtime development signer pin does not match the permanent certificate.' >&2
  exit 1
}
rg --fixed-strings --quiet "EXPECTED_DEV_SIGNER_SHA256: $expected" "$workflow" || {
  echo 'Immutable workflow signer anchor does not match the permanent certificate.' >&2
  exit 1
}

for literal in 'workflow_call:' 'CamX-dev.apk' 'dev-manifest.json' 'dev-latest' \
  'source_sha' 'source_run_id' 'group: camx-dev-ota' 'cancel-in-progress: false' \
  'Reject stale or conflicting rolling publication'; do
  rg --fixed-strings --quiet "$literal" "$workflow" || {
    echo "Development OTA workflow requirement missing: $literal" >&2
    exit 1
  }
done

for literal in 'needs: validate' 'uses: ./.github/workflows/dev-ota.yml' \
  'if: ${{ github.event_name == '\''push'\'' }}' 'lintDevOta'; do
  rg --fixed-strings --quiet "$literal" .github/workflows/ci.yml || {
    echo "Green-publication gate missing: $literal" >&2
    exit 1
  }
done

if rg --fixed-strings --quiet 'secrets: inherit' .github/workflows; then
  echo 'Development publisher must not inherit repository secrets.' >&2
  exit 1
fi

for literal in 'class VerifiedApk private constructor' 'revalidateForInstall()' \
  'fun verifyAndPromote(' 'inspector.inspectInstalled()' 'inspector.inspect(apk)'; do
  rg --fixed-strings --quiet "$literal" app/src/main/java/com/sahidcode404/camx/core/update || {
    echo "Opaque verified APK contract missing: $literal" >&2
    exit 1
  }
done

echo "Development OTA verification passed (signer $expected)."
