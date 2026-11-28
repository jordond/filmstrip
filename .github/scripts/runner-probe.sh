#!/usr/bin/env bash
# Measures how fast this machine is with work that runs no filmstrip code, and appends the figures as one JSON line
# to build/runner-probe/probe.jsonl. A hosted macOS runner is some runs several times slower than others, and these
# figures are what tell a slow machine from a failing test.
#
# Every figure is wall milliseconds. A step that fails or runs past its limit is written as null, so the probe never
# fails a job.
#
#   cpu     a fixed single-thread hash loop
#   disk    writing, syncing and reading back a fixed 200 MB file
#   swift   starting the Swift interpreter on runner-probe.swift, all of it
#   metal   compiling a fixed Metal shader from source, inside that script
#   encode  encoding a fixed 120 frame H.264 clip, inside that script
#   simctl  twenty round trips into the booted simulator, or null with none booted
#
# Usage: runner-probe.sh <label>
set -uo pipefail

label=${1:?usage: runner-probe.sh <label>}
here="$(cd "$(dirname "$0")" && pwd)"
out=build/runner-probe
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$out"

now() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# Runs a command under a limit in seconds and prints how long it took, or null when it failed or ran out of time.
timed() {
  local limit=$1 start
  shift
  start=$(now)
  if perl -e 'alarm shift; exec @ARGV' "$limit" "$@" > "$scratch/stdout" 2> /dev/null; then
    echo $(($(now) - start))
  else
    echo null
  fi
}

disk() {
  dd if=/dev/zero of="$scratch/disk" bs=1048576 count=200 && sync && dd if="$scratch/disk" of=/dev/null bs=1048576
}

simulator() {
  for _ in $(seq 20); do xcrun simctl spawn booted launchctl print system || return 1; done
}

cpu=$(timed 120 python3 -c 'import hashlib
digest = hashlib.sha256()
block = b"x" * 1048576
for _ in range(1500):
    digest.update(block)')
disk=$(export -f disk; export scratch; timed 120 bash -c disk)

metal=null
encode=null
swift=null
if command -v swift > /dev/null; then
  swift=$(timed 300 swift "$here/runner-probe.swift")
  if [ "$swift" != null ]; then
    metal=$(sed -n 's/.*metal=\([0-9a-z]*\).*/\1/p' "$scratch/stdout")
    encode=$(sed -n 's/.*encode=\([0-9a-z]*\).*/\1/p' "$scratch/stdout")
  fi
fi

simctl=null
if command -v xcrun > /dev/null && xcrun simctl list devices booted 2> /dev/null | grep -q Booted; then
  simctl=$(export -f simulator; timed 120 bash -c simulator)
fi

line=$(printf '{"label":"%s","at":"%s","cpu":%s,"disk":%s,"swift":%s,"metal":%s,"encode":%s,"simctl":%s}' \
  "$label" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$cpu" "$disk" "$swift" "${metal:-null}" "${encode:-null}" "$simctl")
echo "$line" >> "$out/probe.jsonl"
echo "$line"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "runner probe: \`$line\`" >> "$GITHUB_STEP_SUMMARY"
fi
