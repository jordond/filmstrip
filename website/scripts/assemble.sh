#!/usr/bin/env bash
# Usage: assemble.sh [--skip-api] [--skip-demo]
# Builds one version of the site into website/dist-version, the one FILMSTRIP_DOCS_VERSION names or the development
# version, then copies in the Dokka HTML as /api, the wasm sample as /demo/app, the Try it
# app as /demo/try and the docs playground as /demo/playground. All must be built first, with `./gradlew dokkaGenerate`,
# `./gradlew :sample:demo:webApp:wasmJsBrowserDistribution`, `./gradlew :sample:tryIt:wasmJsBrowserDistribution` and
# `./gradlew :sample:playground:wasmJsBrowserDistribution`.
# The skip flags are for a local preview without one of them, never for a deploy.
# scripts/assemble-versions.ts then puts the versions together in website/dist, which is what gets deployed.
set -euo pipefail

website=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=$(dirname "$website")
api="$root/dokka"
demo="$root/sample/demo/webApp/build/dist/wasmJs/productionExecutable"
try="$root/sample/tryIt/build/dist/wasmJs/productionExecutable"
playground="$root/sample/playground/build/dist/wasmJs/productionExecutable"

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
  echo "No wasm sample at $demo. Run ./gradlew :sample:demo:webApp:wasmJsBrowserDistribution, or pass --skip-demo." >&2
  exit 1
fi
if ! $skip_demo && [ ! -f "$try/index.html" ]; then
  echo "No Try it app at $try. Run ./gradlew :sample:tryIt:wasmJsBrowserDistribution, or pass --skip-demo." >&2
  exit 1
fi
if ! $skip_demo && [ ! -f "$playground/index.html" ]; then
  echo "No playground at $playground. Run ./gradlew :sample:playground:wasmJsBrowserDistribution, or pass --skip-demo." >&2
  exit 1
fi

cd "$website"
bun x astro build

if ! $skip_api; then
  rm -rf dist-version/api
  cp -R "$api" dist-version/api
  # The reference's fonts.css is the site's own, which reads its fonts from beside the styles directory.
  cp -R src/fonts dist-version/api/fonts
  # The reference's templates load the site's version menu, which Astro only bundles into its own pages.
  bun build src/lib/versionMenu.client.ts --minify --outfile dist-version/api/version-menu.js
fi
if ! $skip_demo; then
  rm -rf dist-version/demo/app
  mkdir -p dist-version/demo
  cp -R "$demo" dist-version/demo/app
  rm -rf dist-version/demo/try
  cp -R "$try" dist-version/demo/try
  rm -rf dist-version/demo/playground
  cp -R "$playground" dist-version/demo/playground
fi
