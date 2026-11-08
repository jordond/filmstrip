#!/usr/bin/env bash
# Reads changed paths, one per line, and prints which fast-lane jobs they reach as GITHUB_OUTPUT lines.
#
#   library=true  a library file, the CI workflow or its scripts changed, so every fast-lane job runs
#   sample=true   a sample file, or a website stylesheet the sample reads, changed
#
# Markdown, the Renovate config, the rest of the website and the other workflows reach neither.
set -euo pipefail

library=false
sample=false

while IFS= read -r path; do
  [ -z "$path" ] && continue
  case "$path" in
    .github/workflows/ci.yml | .github/scripts/*) library=true ;;
    *.md | .github/*) ;;
    website/src/styles/tokens.css | website/src/styles/crt.css) sample=true ;;
    website/*) ;;
    sample/*) sample=true ;;
    *) library=true ;;
  esac
done

echo "library=$library"
echo "sample=$sample"
