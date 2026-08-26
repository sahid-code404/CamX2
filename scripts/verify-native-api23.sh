#!/usr/bin/env bash
set -euo pipefail

if (($# != 1)); then
  echo 'Usage: verify-native-api23.sh APK' >&2
  exit 2
fi

readonly invocation_directory="$PWD"
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$1" in
  /*) apk="$1" ;;
  *) apk="$invocation_directory/$1" ;;
esac
readonly apk

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
readonly expected_ndk_revision='29.0.14206865'
readonly ndk="$ANDROID_HOME/ndk/$expected_ndk_revision"
readonly llvm_bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly readelf="$llvm_bin/llvm-readelf"
readonly objcopy="$llvm_bin/llvm-objcopy"
readonly sysroot_lib="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"

fail() {
  echo "Native API-23 verification failed: $*" >&2
  exit 1
}

test -f "$apk" || fail "APK is missing: $apk"
test -s "$apk" || fail "APK is empty: $apk"
test -x "$readelf" && test -x "$objcopy" || fail 'Pinned NDK ELF tools are missing.'
test -f "$ndk/source.properties" || fail 'Pinned NDK source.properties is missing.'
actual_ndk_revision="$(awk -F= '
  $1 ~ /^[[:space:]]*Pkg\.Revision[[:space:]]*$/ {
    value = $2
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    print value
    exit
  }
' "$ndk/source.properties")"
readonly actual_ndk_revision
test "$actual_ndk_revision" = "$expected_ndk_revision" || {
  fail "NDK revision mismatch: expected $expected_ndk_revision, got $actual_ndk_revision"
}
for command in awk comm grep mktemp od sort stat unzip; do
  command -v "$command" >/dev/null || fail "Required verifier command is missing: $command"
done

scratch="$(mktemp -d "${TMPDIR:-/tmp}/camx-native-api23.XXXXXX")"
readonly scratch
trap 'rm -rf -- "$scratch"' EXIT

archive_listing="$(unzip -Z1 "$apk")" || fail 'APK central directory cannot be read.'
readonly archive_listing
mapfile -t archive_entries <<< "$archive_listing"
declare -a native_entries=()
declare -A native_entry_counts=()
declare -A libraries_by_abi=()

for entry in "${archive_entries[@]}"; do
  [[ "$entry" == lib/* ]] || continue
  if [[ ! "$entry" =~ ^lib/(armeabi-v7a|arm64-v8a|x86|x86_64)/([A-Za-z0-9_.+-]+\.so)$ ]]; then
    fail "unexpected or unsafe native archive entry: $entry"
  fi
  abi="${BASH_REMATCH[1]}"
  library="${BASH_REMATCH[2]}"
  case "$library" in
    libcamx_core.so|libandroidx.graphics.path.so) ;;
    *) fail "unreviewed native library: $entry" ;;
  esac
  native_entries+=("$entry")
  native_entry_counts["$entry"]=$(( ${native_entry_counts["$entry"]:-0} + 1 ))
  libraries_by_abi["$abi"]+="$library"$'\n'
done

readonly -a expected_abis=(armeabi-v7a arm64-v8a x86 x86_64)
readonly expected_library_set=$'libandroidx.graphics.path.so\nlibcamx_core.so'
test "${#native_entries[@]}" -eq 8 || {
  fail "expected exactly eight reviewed native entries, found ${#native_entries[@]}"
}
for abi in "${expected_abis[@]}"; do
  actual_library_set="$(printf '%s' "${libraries_by_abi["$abi"]:-}" | sed '/^$/d' | LC_ALL=C sort -u)"
  test "$actual_library_set" = "$expected_library_set" || {
    fail "native library set mismatch for $abi"
  }
  for library in libandroidx.graphics.path.so libcamx_core.so; do
    entry="lib/$abi/$library"
    test "${native_entry_counts["$entry"]:-0}" -eq 1 || {
      fail "native entry must occur exactly once: $entry"
    }
    mkdir -p "$scratch/lib/$abi"
    unzip -p "$apk" "$entry" > "$scratch/$entry" || fail "cannot extract $entry"
    test -s "$scratch/$entry" || fail "extracted ELF is empty: $entry"
  done
done

verify_elf() {
  local abi="$1"
  local library="$2"
  local elf="$scratch/lib/$abi/$library"
  local expected_class expected_machine target_triple
  case "$abi" in
    armeabi-v7a)
      expected_class='ELF32'
      expected_machine='ARM'
      target_triple='arm-linux-androideabi'
      ;;
    arm64-v8a)
      expected_class='ELF64'
      expected_machine='AArch64'
      target_triple='aarch64-linux-android'
      ;;
    x86)
      expected_class='ELF32'
      expected_machine='Intel 80386'
      target_triple='i686-linux-android'
      ;;
    x86_64)
      expected_class='ELF64'
      expected_machine='Advanced Micro Devices X86-64'
      target_triple='x86_64-linux-android'
      ;;
    *) fail "unreviewed ABI: $abi" ;;
  esac

  local header elf_class elf_data elf_type elf_machine
  header="$($readelf --file-header "$elf")" || fail "cannot read ELF header: lib/$abi/$library"
  elf_class="$(printf '%s\n' "$header" | sed -n 's/^[[:space:]]*Class:[[:space:]]*//p')"
  elf_data="$(printf '%s\n' "$header" | sed -n 's/^[[:space:]]*Data:[[:space:]]*//p')"
  elf_type="$(printf '%s\n' "$header" | sed -n 's/^[[:space:]]*Type:[[:space:]]*//p')"
  elf_machine="$(printf '%s\n' "$header" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
  test "$elf_class" = "$expected_class" || fail "wrong ELF class for lib/$abi/$library: $elf_class"
  test "$elf_data" = "2's complement, little endian" || fail "wrong ELF byte order for lib/$abi/$library: $elf_data"
  test "$elf_type" = 'DYN (Shared object file)' || fail "not a shared object: lib/$abi/$library"
  test "$elf_machine" = "$expected_machine" || fail "wrong machine for lib/$abi/$library: $elf_machine"

  local note_file="$scratch/${abi}-${library}.android-ident"
  "$objcopy" --dump-section ".note.android.ident=$note_file" "$elf" 2>/dev/null || {
    fail "Android API note is missing from lib/$abi/$library"
  }
  test "$(stat -c '%s' "$note_file")" -ge 24 || fail "Android API note is truncated in lib/$abi/$library"
  local note_prefix
  note_prefix="$(od -An -tx1 -N20 "$note_file" | tr -d '[:space:]')"
  test "$note_prefix" = '080000008400000001000000416e64726f696400' || {
    fail "Android API note header is malformed in lib/$abi/$library"
  }
  local api_level
  api_level="$(od -An -tu4 -j20 -N4 "$note_file" | tr -d '[:space:]')"
  [[ "$api_level" =~ ^[0-9]+$ ]] || fail "Android API note is not numeric in lib/$abi/$library"
  ((api_level >= 1 && api_level <= 23)) || {
    fail "lib/$abi/$library was built for Android API $api_level, above the API-23 floor"
  }
  if [[ "$library" == libcamx_core.so ]] && ((api_level != 23)); then
    fail "lib/$abi/$library must be built exactly for Android API 23, got $api_level"
  fi

  local dynamic
  dynamic="$($readelf --dynamic "$elf")" || fail "cannot read dynamic table: lib/$abi/$library"
  if printf '%s\n' "$dynamic" | grep -Eq '\((RPATH|RUNPATH|TEXTREL|RELR|RELRSZ|RELRENT|ANDROID_RELR|ANDROID_RELRSZ|ANDROID_RELRENT)\)'; then
    fail "forbidden RPATH, RUNPATH, TEXTREL, or RELR tag in lib/$abi/$library"
  fi
  if "$readelf" --sections "$elf" | grep -Eq '\.(android_)?relr(\.dyn)?\b'; then
    fail "forbidden RELR section in lib/$abi/$library"
  fi

  mapfile -t sonames < <(printf '%s\n' "$dynamic" | sed -n 's/.*Library soname: \[\([^]]*\)\].*/\1/p')
  test "${#sonames[@]}" -eq 1 && test "${sonames[0]}" = "$library" || {
    fail "SONAME identity mismatch in lib/$abi/$library"
  }

  mapfile -t needed < <(printf '%s\n' "$dynamic" |
    sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' | LC_ALL=C sort)
  readonly_expected_needed=$'libc.so\nlibdl.so\nlibm.so'
  actual_needed="$(printf '%s\n' "${needed[@]}" | sed '/^$/d' | LC_ALL=C sort -u)"
  test "$actual_needed" = "$readonly_expected_needed" || {
    fail "dependency set mismatch in lib/$abi/$library: ${actual_needed//$'\n'/,}"
  }
  test "${#needed[@]}" -eq 3 || fail "duplicate or missing DT_NEEDED entry in lib/$abi/$library"

  local provider_symbols="$scratch/${abi}-${library}.provider-symbols"
  : > "$provider_symbols"
  local provider provider_path
  for provider in "${needed[@]}"; do
    provider_path="$sysroot_lib/$target_triple/23/$provider"
    test -f "$provider_path" || {
      fail "DT_NEEDED provider is unavailable in API-23 NDK stubs: $abi/$provider"
    }
    "$readelf" --dyn-syms --wide "$provider_path" | awk '
      $7 != "UND" && ($5 == "GLOBAL" || $5 == "WEAK") && $6 == "DEFAULT" && $8 != "" {
        name = $8
        sub(/@.*/, "", name)
        print name
      }
    ' >> "$provider_symbols"
  done
  LC_ALL=C sort -u -o "$provider_symbols" "$provider_symbols"

  local strong_imports="$scratch/${abi}-${library}.strong-imports"
  local weak_imports="$scratch/${abi}-${library}.weak-imports"
  "$readelf" --dyn-syms --wide "$elf" | awk '
    $7 == "UND" && $5 != "WEAK" && $8 != "" {
      name = $8
      sub(/@.*/, "", name)
      print name
    }
  ' | LC_ALL=C sort -u > "$strong_imports"
  "$readelf" --dyn-syms --wide "$elf" | awk '
    $7 == "UND" && $5 == "WEAK" && $8 != "" {
      name = $8
      sub(/@.*/, "", name)
      print name
    }
  ' | LC_ALL=C sort -u > "$weak_imports"
  unresolved="$(comm -23 "$strong_imports" "$provider_symbols")"
  test -z "$unresolved" || {
    fail "strong imports lack API-23 providers in lib/$abi/$library: ${unresolved//$'\n'/,}"
  }

  local expected_weak_imports=''
  if [[ "$abi" == arm64-v8a && "$library" == libcamx_core.so ]]; then
    expected_weak_imports='memfd_create'
  fi
  actual_weak_imports="$(<"$weak_imports")"
  test "$actual_weak_imports" = "$expected_weak_imports" || {
    fail "weak-import allowlist mismatch in lib/$abi/$library: ${actual_weak_imports:-none}"
  }

  local exports="$scratch/${abi}-${library}.exports"
  "$readelf" --dyn-syms --wide "$elf" | awk '
    $7 != "UND" && ($5 == "GLOBAL" || $5 == "WEAK") && $6 == "DEFAULT" && $8 != "" {
      name = $8
      sub(/@.*/, "", name)
      print name
    }
  ' | LC_ALL=C sort -u > "$exports"
  local expected_exports
  case "$library" in
    libcamx_core.so)
      expected_exports=$'Java_com_sahidcode404_camx_core_camera_diagnostics_NativeCore_nativeSnapshot\nJava_com_sahidcode404_camx_core_camera_discovery_NdkAdvertisedNativeBridge_nativeCollect\nJava_com_sahidcode404_camx_core_camera_discovery_NdkDeepNativeBridge_nativeCollectCandidates'
      ;;
    libandroidx.graphics.path.so)
      expected_exports='JNI_OnLoad'
      ;;
    *) fail "no export policy for lib/$abi/$library" ;;
  esac
  actual_exports="$(<"$exports")"
  test "$actual_exports" = "$expected_exports" || {
    fail "export allowlist mismatch in lib/$abi/$library: ${actual_exports:-none}"
  }

  echo "Verified lib/$abi/$library (Android API $api_level, $elf_class, providers=${actual_needed//$'\n'/,})."
}

for abi in "${expected_abis[@]}"; do
  verify_elf "$abi" libandroidx.graphics.path.so
  verify_elf "$abi" libcamx_core.so
done

echo "Native API-23 ELF verification passed (${#native_entries[@]} libraries across ${#expected_abis[@]} ABIs)."
