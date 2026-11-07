#!/usr/bin/env bash
# Usage: assemble.sh [--skip-api] [--skip-demo]
# Builds the site into website/dist, then copies in the Dokka HTML as /api, the wasm sample as /demo/app and the Try it
# app as /demo/try. All must be built first, with `./gradlew dokkaGenerate`,
# `./gradlew :sample:webApp:wasmJsBrowserDistribution` and `./gradlew :sample:tryIt:wasmJsBrowserDistribution`.
# The skip flags are for a local preview without one of them, never for a deploy.
set -euo pipefail

website=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=$(dirname "$website")
api="$root/dokka"
demo="$root/sample/webApp/build/dist/wasmJs/productionExecutable"
try="$root/sample/tryIt/build/dist/wasmJs/productionExecutable"

skip_api=false
skip_demo=false
for arg in "$@"; do
  case $arg in
    --skip-api) skip_api=true ;;
    --skip-demo) skip_demo=true ;;
    *)
      echo "usage: assemble.sh [--skip-api] [--skip-demo]" >&2
      exit 2
      ;;
  esac
done

if ! $skip_api && [ ! -f "$api/index.html" ]; then
  echo "No Dokka output at $api. Run ./gradlew dokkaGenerate, or pass --skip-api." >&2
  exit 1
fi
if ! $skip_demo && [ ! -f "$demo/index.html" ]; then
  echo "No wasm sample at $demo. Run ./gradlew :sample:webApp:wasmJsBrowserDistribution, or pass --skip-demo." >&2
  exit 1
fi
if ! $skip_demo && [ ! -f "$try/index.html" ]; then
  echo "No Try it app at $try. Run ./gradlew :sample:tryIt:wasmJsBrowserDistribution, or pass --skip-demo." >&2
  exit 1
fi

cd "$website"
bun x astro build

if ! $skip_api; then
  rm -rf dist/api
  cp -R "$api" dist/api
fi
if ! $skip_demo; then
  rm -rf dist/demo/app
  mkdir -p dist/demo
  cp -R "$demo" dist/demo/app
  rm -rf dist/demo/try
  cp -R "$try" dist/demo/try
fi
