#!/usr/bin/env bash
# Fails unless the ffmpeg on PATH is a release of the major named in INTENDED_MAJOR.
#
# This checks the cell got the major it means to test. Whether a build meets the backend's floor is
# checkFfmpegTestToolchain's call, which every ffmpeg jvmTest runs first with the backend's own rule.
set -euo pipefail

: "${INTENDED_MAJOR:?set INTENDED_MAJOR to the ffmpeg major this cell tests}"

banner=$(ffmpeg -version | head -1)
echo "$banner"
major=$(echo "$banner" | sed -nE 's/^ffmpeg version n?([0-9]+)\..*/\1/p')
if [ "$major" != "$INTENDED_MAJOR" ]; then
  echo "::error::expected a release of ffmpeg $INTENDED_MAJOR, got '$banner'"
  exit 1
fi
