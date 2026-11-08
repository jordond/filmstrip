#!/usr/bin/env bash
# Fails when the ios matrix in media.yml and the modules whose iOS simulator tests run a class under the iOS-only
# rule differ. Every such module sits in exactly one group's modules, and no other module is in a group, since it
# would only link there.
# The modules come from `./gradlew -q printIosTestModules`, or from a file of one per line given second.
set -euo pipefail

workflow="${1:-.github/workflows/media.yml}"

if [ -n "${2:-}" ]; then
  expected=$(sort -u "$2")
else
  expected=$(./gradlew -q printIosTestModules | sort -u)
fi

# Every word of each modules: "..." entry under the ios job, up to the next job.
grouped=$(awk '
  /^  ios:$/ { in_job = 1; next }
  in_job && /^  [a-z]/ { exit }
  in_job && /modules: "/ {
    line = $0
    sub(/.*modules: "/, "", line)
    sub(/".*/, "", line)
    n = split(line, words, " ")
    for (i = 1; i <= n; i++) print words[i]
  }
' "$workflow")

twice=$(echo "$grouped" | sort | uniq -d)
listed=$(echo "$grouped" | sort -u)

missing=$(comm -23 <(echo "$expected") <(echo "$listed"))
extra=$(comm -13 <(echo "$expected") <(echo "$listed"))

if [ -n "$missing" ] || [ -n "$extra" ] || [ -n "$twice" ]; then
  [ -n "$missing" ] && echo "Modules with iOS-only tests in no ios group of $workflow:" && echo "$missing"
  [ -n "$extra" ] && echo "Modules in the ios matrix of $workflow with no iOS-only tests:" && echo "$extra"
  [ -n "$twice" ] && echo "Modules in more than one ios group of $workflow:" && echo "$twice"
  exit 1
fi
echo "The ios matrix covers all $(echo "$expected" | wc -l | tr -d ' ') modules with iOS-only tests."
