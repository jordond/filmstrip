#!/usr/bin/env bash
#
# Publishes build/ to the R2 bucket the sample's clip list reads from.
#
# Needs wrangler logged in to the account that owns the bucket. The bucket serves
# filmstrip-media.jordond.dev and carries the CORS rules in cors.json, which the browser build
# needs to read a clip at all.

set -euo pipefail

cd "$(dirname "$0")"

bucket="${FILMSTRIP_MEDIA_BUCKET:-filmstrip-media}"
export CLOUDFLARE_ACCOUNT_ID="${CLOUDFLARE_ACCOUNT_ID:-bca591069b9efc9b82571ab6301ca60c}"

for file in build/*; do
  name="$(basename "$file")"
  case "$name" in
  *.mp4) type="video/mp4" ;;
  *.mkv) type="video/x-matroska" ;;
  *.webm) type="video/webm" ;;
  *) type="application/octet-stream" ;;
  esac

  echo "$name"
  wrangler r2 object put "$bucket/$name" --file "$file" --content-type "$type" --remote >/dev/null
done

echo
echo "Published to https://filmstrip-media.jordond.dev/"
