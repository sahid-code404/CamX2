#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly build_file="app/build.gradle.kts"
readonly workflow=".github/workflows/dev-ota.yml"
readonly expected_file="tools/dev-signing/EXPECTED_CERT_SHA256"
readonly encoded_signer="tools/dev-signing/camx-dev.jks.b64"
readonly verifier="app/src/main/java/com/sahidcode404/camx/core/update/verification/DevelopmentUpdateVerifier.kt"
readonly update_root="app/src/main/java/com/sahidcode404/camx/core/update"
readonly update_repository="$update_root/UpdateRepository.kt"
readonly update_network="$update_root/DevelopmentUpdateNetwork.kt"
readonly update_store="$update_root/UpdateFileStore.kt"
readonly dev_manifest="app/src/devOta/AndroidManifest.xml"

for file in "$workflow" "$expected_file" "$encoded_signer" "$verifier" \
  "$update_repository" "$update_network" "$update_store" "$dev_manifest"; do
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

for literal in \
  'https://github.com/sahid-code404/CamX/releases/download/dev-latest/dev-manifest.json' \
  'https://github.com/sahid-code404/CamX/releases/download/dev-latest/CamX-dev.apk' \
  'const val CONNECT_TIMEOUT_MILLIS = 10_000' \
  'const val READ_TIMEOUT_MILLIS = 20_000' \
  'const val MAX_REDIRECTS = 5'; do
  rg --fixed-strings --quiet "$literal" "$update_network" || {
    echo "CAMX-111 fixed network contract missing: $literal" >&2
    exit 1
  }
done

for literal in \
  'suspend fun checkAfterFirstFrame()' \
  'suspend fun checkManually()' \
  'suspend fun downloadAvailable()' \
  'fun cancel()' \
  'FirstPreviewGate' \
  'Dispatchers.IO' \
  '${DevOtaTrust.APK_ASSET_NAME}.part'; do
  rg --fixed-strings --quiet "$literal" "$update_root" || {
    echo "CAMX-111 repository contract missing: $literal" >&2
    exit 1
  }
done

for permission in \
  'android.permission.INTERNET' \
  'android.permission.REQUEST_INSTALL_PACKAGES'; do
  rg --fixed-strings --quiet "$permission" "$dev_manifest" || {
    echo "devOta manifest permission missing: $permission" >&2
    exit 1
  }
done

for permission in \
  'android.permission.INTERNET' \
  'android.permission.REQUEST_INSTALL_PACKAGES'; do
  if rg --fixed-strings --quiet "$permission" app/src/main/AndroidManifest.xml; then
    echo "$permission must stay scoped to the devOta variant." >&2
    exit 1
  fi
done

rg --fixed-strings --quiet 'BuildConfig.OTA_CHANNEL == DevOtaTrust.CHANNEL' \
  "$update_root/DevelopmentUpdateViewModel.kt" || {
  echo 'CAMX-111 must remain gated to the devOta development channel.' >&2
  exit 1
}

if rg --line-number '^import com\.sahidcode404\.camx\.core\.camera' "$update_root" --glob '*.kt'; then
  echo 'Development OTA core must not depend on camera topology/session types.' >&2
  exit 1
fi

resume_body="$(awk '
  /^[[:space:]]*fun onHostResumed\(\)/ { capture = 1 }
  capture && /^[[:space:]]*override fun onCleared\(\)/ { exit }
  capture { print }
' "$update_root/DevelopmentUpdateViewModel.kt")"
test -n "$resume_body" || { echo 'Could not isolate OTA onHostResumed().' >&2; exit 1; }
if printf '%s\n' "$resume_body" | rg --fixed-strings --quiet 'installReadyUpdate()'; then
  echo 'Returning from unknown-source settings must not auto-launch installation.' >&2
  exit 1
fi

if rg --line-number '\b(Service|ForegroundService|JobService|WorkManager)\b' "$update_root" --glob '*.kt'; then
  echo 'Development OTA must not introduce a background service/worker.' >&2
  exit 1
fi

echo "Development OTA verification passed (signer $expected)."
