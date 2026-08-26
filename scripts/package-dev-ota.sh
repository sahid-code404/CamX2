#!/usr/bin/env bash
set -euo pipefail

if (($# != 3)); then
  echo 'Usage: package-dev-ota.sh APK OUTPUT_DIRECTORY GIT_SHA' >&2
  exit 2
fi

readonly invocation_directory="$PWD"
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$1" in
  /*) apk="$1" ;;
  *) apk="$invocation_directory/$1" ;;
esac
case "$2" in
  /*) output="$2" ;;
  *) output="$invocation_directory/$2" ;;
esac
readonly apk output
readonly git_sha="$3"
cd "$root"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
readonly build_tools="$ANDROID_HOME/build-tools/37.0.0"
readonly aapt="$build_tools/aapt"
readonly apksigner="$build_tools/apksigner"
expected_signer="$(tr -d '[:space:]' < tools/dev-signing/EXPECTED_CERT_SHA256)"
readonly expected_signer

test -f "$apk" || { echo "APK missing: $apk" >&2; exit 1; }
test -x "$aapt" && test -x "$apksigner" || { echo 'Pinned Android build tools are missing.' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq is required to package OTA metadata.' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required to package OTA metadata.' >&2; exit 1; }
[[ "$expected_signer" =~ ^[0-9a-f]{64}$ ]] || { echo 'Pinned signer digest is malformed.' >&2; exit 1; }
[[ "$git_sha" =~ ^[0-9a-fA-F]{7,64}$ ]] || { echo 'Git SHA must be 7 to 64 hexadecimal characters.' >&2; exit 1; }

mkdir -p "$output"
install -m 0644 "$apk" "$output/CamX-dev.apk"
readonly packaged_apk="$output/CamX-dev.apk"
badging="$($aapt dump badging "$packaged_apk")"
readonly badging
application_id="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
version_code="$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)"
version_name="$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
min_sdk="$(printf '%s\n' "$badging" | sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)"
apk_sha="$(sha256sum "$packaged_apk" | awk '{print $1}')"
signer_output="$($apksigner verify --verbose --print-certs "$packaged_apk" 2>&1)"
signer_count="$(printf '%s\n' "$signer_output" | sed -n 's/^Number of signers:[[:space:]]*//p')"
signer_sha="$(printf '%s\n' "$signer_output" | sed -n 's/.*certificate SHA-256 digest:[[:space:]]*//p' | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
readonly application_id version_code version_name min_sdk apk_sha signer_output signer_count signer_sha

test "$application_id" = 'com.sahidcode404.camx2' || { echo "Wrong package: $application_id" >&2; exit 1; }
[[ "$version_code" =~ ^[0-9]+$ ]] && ((version_code >= 1 && version_code <= 2100000000)) || {
  echo "Invalid APK versionCode: $version_code" >&2
  exit 1
}
test -n "$version_name" && ((${#version_name} <= 128)) || { echo 'Invalid APK versionName.' >&2; exit 1; }
test "$min_sdk" = 23 || { echo "CamX development APK must declare minSdk 23, got: $min_sdk" >&2; exit 1; }
test "$signer_count" = 1 || { echo "Expected exactly one APK signer, found: $signer_count" >&2; exit 1; }
printf '%s\n' "$signer_output" | grep -Fqx 'Verified using v1 scheme (JAR signing): true' || {
  echo 'APK Signature Scheme v1 is required for Android API 23.' >&2
  exit 1
}
printf '%s\n' "$signer_output" | grep -Fqx 'Verified using v2 scheme (APK Signature Scheme v2): true' || {
  echo 'APK Signature Scheme v2 verification is required.' >&2
  exit 1
}
test "$signer_sha" = "$expected_signer" || { echo "Wrong signer: $signer_sha" >&2; exit 1; }

build_timestamp="${CAMX_BUILD_TIMESTAMP_UTC:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
changelog="${CAMX_CHANGELOG:-Architecture foundation}"
readonly build_timestamp changelog
test -n "$build_timestamp" && ((${#build_timestamp} <= 64)) || { echo 'Invalid build timestamp.' >&2; exit 1; }
((${#changelog} <= 4096)) || { echo 'Changelog exceeds 4096 characters.' >&2; exit 1; }

jq --null-input \
  --arg channel development \
  --arg applicationId "$application_id" \
  --arg versionName "$version_name" \
  --arg apkAssetName 'CamX-dev.apk' \
  --arg sha256 "$apk_sha" \
  --arg signingCertSha256 "$signer_sha" \
  --arg gitSha "${git_sha,,}" \
  --arg buildTimestamp "$build_timestamp" \
  --arg changelog "$changelog" \
  --argjson schema 1 \
  --argjson versionCode "$version_code" \
  --argjson minSdk "$min_sdk" \
  --argjson mandatory false \
  '{schema:$schema,channel:$channel,applicationId:$applicationId,versionCode:$versionCode,
    versionName:$versionName,minSdk:$minSdk,apkAssetName:$apkAssetName,sha256:$sha256,
    signingCertSha256:$signingCertSha256,gitSha:$gitSha,buildTimestamp:$buildTimestamp,
    changelog:$changelog,mandatory:$mandatory}' > "$output/dev-manifest.json"

(
  cd "$output"
  sha256sum CamX-dev.apk dev-manifest.json > SHA256SUMS.txt
  sha256sum --check SHA256SUMS.txt
)

printf '%s\n' "$signer_sha" > "$output/SIGNING_CERT_SHA256.txt"
printf '%s\n' "$version_code" > "$output/VERSION_CODE.txt"
printf '%s\n' "$version_name" > "$output/VERSION_NAME.txt"
printf '%s\n' "${git_sha,,}" > "$output/GIT_SHA.txt"

echo "Packaged CamX2 dev OTA $version_name ($version_code), signer $signer_sha."
