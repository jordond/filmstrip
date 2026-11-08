#!/usr/bin/env bash
# Fails when the ios matrix in media.yml and the modules with iOS simulator tests differ.
# The modules come from `./gradlew -q printIosTestModules`, or from a file of one per line given second.
set -euo pipefail

workflow="${1:-.github/workflows/media.yml}"

if [ -n "${2:-}" ]; then
  expected=$(sort -u "$2")
else
  expected=$(./gradlew -q printIosTestModules | sort -u)
fi

# The module list under the ios job's matrix, up to the first line that is not a list entry.
listed=$(awk '
  /^  ios:$/ { in_job = 1; next }
  in_job && /^  [a-z]/ { exit }
  in_job && /^ +module:$/ { in_list = 1; next }
  in_list && /^ +- / { sub(/^ +- /, ""); print; next }
  in_list { exit }
' "$workflow" | sort -u)

missing=$(comm -23 <(echo "$expected") <(echo "$listed"))
extra=$(comm -13 <(echo "$expected") <(echo "$listed"))

if [ -n "$missing" ] || [ -n "$extra" ]; then
  [ -n "$missing" ] && echo "Modules with iOS tests missing from the ios matrix in $workflow:" && echo "$missing"
  [ -n "$extra" ] && echo "Modules in the ios matrix of $workflow with no iOS tests:" && echo "$extra"
  exit 1
fi
echo "The ios matrix lists all $(echo "$listed" | wc -l | tr -d ' ') modules with iOS tests."
