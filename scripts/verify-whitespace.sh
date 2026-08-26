#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

git diff --check
git diff --cached --check

if rg --hidden --line-number '[[:blank:]]+$' --glob '!.git/**' \
  --glob '!tools/dev-signing/*.b64' --glob '!gradlew.bat' \
  --glob '*.kt' --glob '*.kts' --glob '*.cpp' --glob '*.hpp' --glob '*.md' --glob '*.sh' \
  --glob '*.yml' --glob '*.xml' .; then
  echo 'Whitespace verification failed.' >&2
  exit 1
fi

echo 'Whitespace verification passed.'
