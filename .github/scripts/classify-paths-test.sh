#!/usr/bin/env bash
# Checks classify-paths.sh against a table of changed path sets.
set -euo pipefail

classify="$(dirname "$0")/classify-paths.sh"
failed=0

check() {
  local name=$1 paths=$2 expected=$3 actual
  actual=$(printf '%b' "$paths" | bash "$classify" | tr '\n' ' ')
  if [ "$actual" = "$expected " ]; then
    echo "ok    $name"
  else
    echo "FAIL  $name: expected '$expected', got '$actual'"
    failed=1
  fi
}

check "sample file" 'sample/common/src/commonMain/kotlin/App.kt\n' "library=false sample=true"
check "tokens stylesheet" 'website/src/styles/tokens.css\n' "library=false sample=true"
check "crt stylesheet" 'website/src/styles/crt.css\n' "library=false sample=true"
check "website page" 'website/src/content/docs/index.mdx\n' "library=false sample=false"
check "library file" 'filmstrip-core/src/commonMain/kotlin/Edit.kt\n' "library=true sample=false"
check "ci workflow" '.github/workflows/ci.yml\n' "library=true sample=false"
check "ci script" '.github/scripts/slowest-tests.py\n' "library=true sample=false"
check "media workflow" '.github/workflows/media.yml\n' "library=false sample=false"
check "renovate config" '.github/renovate.json\n' "library=false sample=false"
check "markdown" 'README.md\nfilmstrip-core/NOTES.md\n' "library=false sample=false"
check "build logic" 'build-logic/convention/src/main/kotlin/TestLanes.kt\n' "library=true sample=false"
check "mixed" 'sample/webApp/build.gradle.kts\nREADME.md\nfilmstrip-player/build.gradle.kts\n' \
  "library=true sample=true"
check "nothing" '' "library=false sample=false"

exit $failed
