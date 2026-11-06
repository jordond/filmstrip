#!/usr/bin/env bash
# Usage: sticky-comment.sh KEY BODY_FILE
# Posts BODY_FILE on pull request $PR, or replaces the comment posted earlier with the same KEY.
# Needs GH_TOKEN, GITHUB_REPOSITORY and PR.
set -euo pipefail

key=${1:?usage: sticky-comment.sh KEY BODY_FILE}
file=${2:?usage: sticky-comment.sh KEY BODY_FILE}
marker="<!-- filmstrip-comment: $key -->"

# GitHub refuses comments over 65536 characters. The body goes through a file, since one that
# size is also too long for a command-line argument.
limit=60000
body=$(mktemp)
trap 'rm -f "$body"' EXIT
{
  echo "$marker"
  if [ "$(wc -c <"$file")" -gt "$limit" ]; then
    head -c "$limit" "$file"
    printf '\n\n_Cut to %d bytes._\n' "$limit"
  else
    cat "$file"
  fi
} >"$body"

id=$(gh api "repos/$GITHUB_REPOSITORY/issues/$PR/comments" --paginate \
  --jq ".[] | select(.user.type == \"Bot\" and (.body | startswith(\"$marker\"))) | .id" | head -n 1)

if [ -n "$id" ]; then
  gh api -X PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$id" -F body=@"$body" >/dev/null
else
  gh api -X POST "repos/$GITHUB_REPOSITORY/issues/$PR/comments" -F body=@"$body" >/dev/null
fi
echo "commented $key on #$PR"
