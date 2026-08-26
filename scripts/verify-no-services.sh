#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
failures=0

if rg --line-number '<service\b|<receiver\b' app/src/main/AndroidManifest.xml; then
  echo 'Component violation: background service/receiver declared.' >&2
  failures=$((failures + 1))
fi
if rg --line-number \
  '\b(?:Service|ForegroundService|JobService|WorkManager|CoroutineWorker|ListenableWorker)\b' \
  --glob '*.kt' --glob '*.java' app/src/main/java; then
  echo '::error title=Background component policy violation::Service or worker API found in app source.'
  echo 'Component violation: service/worker API in application source.' >&2
  failures=$((failures + 1))
fi

((failures == 0)) || exit 1
echo 'No-service verification passed.'
