#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
readonly sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
test -x "$sdkmanager" || { echo "sdkmanager missing: $sdkmanager" >&2; exit 1; }

set +e
yes | "$sdkmanager" --licenses >/dev/null
readonly license_statuses=("${PIPESTATUS[@]}")
set -e
if ((license_statuses[1] != 0)); then
  echo "sdkmanager license acceptance failed with exit ${license_statuses[1]}." >&2
  exit "${license_statuses[1]}"
fi
"$sdkmanager" \
  'platform-tools' \
  'platforms;android-37.0' \
  'build-tools;37.0.0' \
  'ndk;29.0.14206865' \
  'cmake;4.1.2'

read_property() {
  local file="$1"
  local property="$2"
  awk -F= -v expected_key="$property" '
    {
      key = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
    }
    key == expected_key {
      value = substr($0, index($0, "=") + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$file"
}

verify_revision() {
  local package_name="$1"
  local properties_file="$2"
  local expected_revision="$3"
  test -f "$properties_file" || {
    echo "Installed $package_name has no source.properties file." >&2
    exit 1
  }
  local actual_revision
  actual_revision="$(read_property "$properties_file" Pkg.Revision)"
  test "$actual_revision" = "$expected_revision" || {
    echo "$package_name revision mismatch: expected $expected_revision, got $actual_revision" >&2
    exit 1
  }
}

verify_revision 'Android SDK Platform 37.0' \
  "$ANDROID_HOME/platforms/android-37.0/source.properties" 2
verify_revision 'Android SDK Build Tools' \
  "$ANDROID_HOME/build-tools/37.0.0/source.properties" 37.0.0
verify_revision 'Android NDK' \
  "$ANDROID_HOME/ndk/29.0.14206865/source.properties" 29.0.14206865
verify_revision 'CMake' \
  "$ANDROID_HOME/cmake/4.1.2/source.properties" 4.1.2

echo 'Pinned Android SDK, NDK, and CMake revisions verified.'
