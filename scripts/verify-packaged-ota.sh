#!/usr/bin/env bash
set -euo pipefail

if (($# != 1)); then
  echo 'Usage: verify-packaged-ota.sh ARTIFACT_DIRECTORY' >&2
  exit 2
fi

readonly invocation_directory="$PWD"
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$1" in
  /*) directory="$1" ;;
  *) directory="$invocation_directory/$1" ;;
esac
readonly directory
cd "$root"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
readonly aapt="$ANDROID_HOME/build-tools/37.0.0/aapt"
readonly apksigner="$ANDROID_HOME/build-tools/37.0.0/apksigner"
readonly apk="$directory/CamX-dev.apk"
readonly manifest="$directory/dev-manifest.json"
expected_signer="$(tr -d '[:space:]' < tools/dev-signing/EXPECTED_CERT_SHA256)"
readonly expected_signer

test -x "$aapt" && test -x "$apksigner" || { echo 'Pinned Android build tools are missing.' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq is required to verify OTA metadata.' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required to verify OTA metadata.' >&2; exit 1; }
command -v unzip >/dev/null || { echo 'unzip is required to inspect packaged native libraries.' >&2; exit 1; }
[[ "$expected_signer" =~ ^[0-9a-f]{64}$ ]] || { echo 'Pinned signer digest is malformed.' >&2; exit 1; }
test -f "$apk" && test -f "$manifest" || { echo 'Packaged OTA assets are incomplete.' >&2; exit 1; }

jq --exit-status '
  type == "object" and
  (keys == (["schema", "channel", "applicationId", "versionCode", "versionName", "minSdk",
    "apkAssetName", "sha256", "signingCertSha256", "gitSha", "buildTimestamp", "changelog",
    "mandatory"] | sort)) and
  .schema == 1 and
  .channel == "development" and
  .applicationId == "com.sahidcode404.camx" and
  (.versionCode | type == "number" and . >= 1 and . <= 2100000000 and . == floor) and
  (.versionName | type == "string" and length >= 1 and length <= 128) and
  .minSdk == 23 and
  .apkAssetName == "CamX-dev.apk" and
  (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.signingCertSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.gitSha | type == "string" and test("^[0-9a-f]{7,64}$")) and
  (.buildTimestamp | type == "string" and length >= 1 and length <= 64) and
  (.changelog | type == "string" and length <= 4096) and
  .mandatory == false
' "$manifest" >/dev/null || { echo 'Development manifest contract validation failed.' >&2; exit 1; }

manifest_sha="$(jq -er '.sha256' "$manifest")"
actual_sha="$(sha256sum "$apk" | awk '{print $1}')"
manifest_signer="$(jq -er '.signingCertSha256' "$manifest")"
signer_output="$($apksigner verify --verbose --print-certs "$apk" 2>&1)"
signer_count="$(printf '%s\n' "$signer_output" | sed -n 's/^Number of signers:[[:space:]]*//p')"
actual_signer="$(printf '%s\n' "$signer_output" | sed -n 's/.*certificate SHA-256 digest:[[:space:]]*//p' | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
readonly manifest_sha actual_sha manifest_signer signer_output signer_count actual_signer

test "$manifest_sha" = "$actual_sha" || { echo 'Packaged APK SHA does not match manifest.' >&2; exit 1; }
test "$signer_count" = 1 || { echo "Expected exactly one APK signer, found: $signer_count" >&2; exit 1; }
printf '%s\n' "$signer_output" | grep -Fqx 'Verified using v1 scheme (JAR signing): true' || {
  echo 'APK Signature Scheme v1 is required for Android API 23.' >&2
  exit 1
}
printf '%s\n' "$signer_output" | grep -Fqx 'Verified using v2 scheme (APK Signature Scheme v2): true' || {
  echo 'APK Signature Scheme v2 verification is required.' >&2
  exit 1
}
test "$manifest_signer" = "$expected_signer" && test "$actual_signer" = "$expected_signer" || {
  echo 'Packaged APK signer continuity check failed.' >&2
  exit 1
}

badging="$($aapt dump badging "$apk")"
package_name="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
version_code="$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)"
version_name="$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
min_sdk="$(printf '%s\n' "$badging" | sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)"
readonly badging package_name version_code version_name min_sdk

test "$package_name" = 'com.sahidcode404.camx' || { echo 'APK package is not the fixed CamX ID.' >&2; exit 1; }
test "$package_name" = "$(jq -er '.applicationId' "$manifest")" || { echo 'Package mismatch.' >&2; exit 1; }
test "$version_code" = "$(jq -er '.versionCode | tostring' "$manifest")" || { echo 'versionCode mismatch.' >&2; exit 1; }
test "$version_name" = "$(jq -er '.versionName' "$manifest")" || { echo 'versionName mismatch.' >&2; exit 1; }
test "$min_sdk" = 23 || { echo "APK minSdk must remain 23, got: $min_sdk" >&2; exit 1; }
test "$min_sdk" = "$(jq -er '.minSdk | tostring' "$manifest")" || { echo 'minSdk mismatch.' >&2; exit 1; }

archive_entries="$(unzip -Z1 "$apk")"
readonly archive_entries
readonly -a required_native_libraries=(
  'lib/armeabi-v7a/libcamx_core.so'
  'lib/arm64-v8a/libcamx_core.so'
  'lib/x86/libcamx_core.so'
  'lib/x86_64/libcamx_core.so'
)
for native_library in "${required_native_libraries[@]}"; do
  printf '%s\n' "$archive_entries" | grep -Fqx "$native_library" || {
    echo "Packaged APK is missing required native library: $native_library" >&2
    exit 1
  }
done

if [[ -f "$directory/SHA256SUMS.txt" ]]; then
  (cd "$directory" && sha256sum --check SHA256SUMS.txt)
fi

echo "Packaged OTA verification passed ($package_name versionCode=$version_code signer=$actual_signer)."
