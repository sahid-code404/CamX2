#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly output="$root/build/native-host-tests"
mkdir -p "$output"

"${CXX:-c++}" \
  -std=c++20 -Wall -Wextra -Werror -pedantic -pthread \
  -I"$root/native/core/include" \
  "$root/native/core/tests/native_core_test.cpp" \
  "$root/native/core/src/discovery/ndk_advertised_metadata.cpp" \
  "$root/native/core/src/buffer/native_buffer_pool.cpp" \
  "$root/native/core/src/memory/resource_counters.cpp" \
  "$root/native/core/src/trace/native_trace_buffer.cpp" \
  -o "$output/native_core_test"

"$output/native_core_test"
echo 'Native host tests passed.'
