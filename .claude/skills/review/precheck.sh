#!/usr/bin/env bash
# Mechanical pre-review gate over the Kotlin a branch changed.
# Usage: precheck.sh <base> <branch | worktree dir>
# Prints `path:line: reason` per hit. Exit 0 when clean, 1 on any hit, 2 on bad usage.
# Line checks read only the lines the diff adds. The size check compares the whole file at base and head.
# Worktree mode also reads untracked .kt files, every line counted as added.

set -u
set -f

if [ $# -ne 2 ]; then
  echo "usage: precheck.sh <base> <branch | worktree dir>" >&2
  exit 2
fi

base=$1
target=$2
untracked=""

if [ -d "$target" ]; then
  repo=$(git -C "$target" rev-parse --show-toplevel) || exit 2
  mb=$(git -C "$repo" merge-base "$base" HEAD) || exit 2
  range=$mb
  show() { cat "$repo/$1" 2>/dev/null; }
  untracked=$(git -C "$repo" ls-files --others --exclude-standard -- '*.kt') || exit 2
else
  repo=$(git rev-parse --show-toplevel) || exit 2
  git rev-parse --verify --quiet "$target^{commit}" >/dev/null || {
    echo "precheck: no branch or directory $target" >&2
    exit 2
  }
  mb=$(git -C "$repo" merge-base "$base" "$target") || exit 2
  range="$base...$target"
  show() { git -C "$repo" show "$target:$1" 2>/dev/null; }
fi

# status, then the base path, then the head path. A rename reads its size at base from the old path.
changes=$(git -C "$repo" diff --name-status -M --diff-filter=ACMR "$range" -- '*.kt' |
  awk -F '\t' '{ if (NF == 3) print $2 "\t" $3; else print $2 "\t" $2 }') || exit 2
for f in $untracked; do changes=$(printf '%s\n%s\t%s' "$changes" "-" "$f"); done
[ -n "$(printf '%s' "$changes" | tr -d '[:space:]')" ] || exit 0

files=$(printf '%s\n' "$changes" | awk -F '\t' 'NF == 2 { print $2 }')

# A module has a Native target when its build file applies a convention plugin that adds one, which is
# filmstrip.library, filmstrip.library.compose or filmstrip.library.apple, or declares an ios or macos target
# itself. filmstrip.library.jvm, .android and .web add none, so commonTest there is free to use commas.
nonnative=" "
for f in $files; do
  case $f in */src/*) ;; *) continue ;; esac
  mod=${f%%/src/*}
  case $nonnative in *" $mod "*) continue ;; esac
  build=$(show "$mod/build.gradle.kts")
  [ -n "$build" ] || continue
  if ! printf '%s\n' "$build" |
    grep -Eq 'id\("filmstrip\.library(\.compose|\.apple)?"\)|(ios|macos)[A-Za-z0-9]*\(\)'; then
    nonnative="$nonnative$mod "
  fi
done

hits=$(
  printf '%s\n' "$changes" | while IFS="$(printf '\t')" read -r old new; do
    [ -n "$new" ] || continue
    head_lines=$(show "$new" | wc -l | tr -d ' ')
    if [ "$old" = "-" ]; then
      base_lines=0
    else
      base_lines=$(git -C "$repo" show "$mb:$old" 2>/dev/null | wc -l | tr -d ' ')
    fi
    if [ "$base_lines" -le 500 ] && [ "$head_lines" -gt 500 ]; then
      echo "$new:$head_lines: file is $head_lines lines, over 500"
    fi
  done

  {
    tracked=$(printf '%s\n' "$changes" | awk -F '\t' 'NF == 2 && $1 != "-" { print $2 }')
    [ -z "$tracked" ] || git -C "$repo" diff -U0 -M --no-color "$range" -- '*.kt'
    for f in $untracked; do
      (cd "$repo" && git diff --no-index -U0 --no-color /dev/null "$f")
    done
  } | LC_ALL=C awk -v nonnative="$nonnative" '
    /^\+\+\+ / { path = substr($0, 7); next }
    /^@@ / {
      split($3, a, ",")
      n = substr(a[1], 2) + 0
      next
    }
    /^\+/ {
      line = substr($0, 2)
      chars = line
      gsub(/[\200-\277]/, "", chars)
      if (length(chars) > 120) print path ":" n ": " length(chars) " columns, over 120"
      # A plan marker is the whole comment, optionally followed by a colon and a note, so `// bt709-m1 matrix` passes.
      if (line ~ /\/\/ [a-z0-9]+(-[a-z0-9]+)*-[um][0-9]+(:.*)?[ \t]*$/) print path ":" n ": leftover plan marker"
      mod = path
      sub(/\/src\/.*/, "", mod)
      native_common = path ~ /\/src\/commonTest\// && index(nonnative, " " mod " ") == 0
      if ((native_common || path ~ /\/src\/(apple|ios|macos|native)[A-Za-z0-9]*Test\//) &&
          line ~ /fun `[^`]*,[^`]*`/) print path ":" n ": comma in a Native test name"
      n++
    }
  '
)

[ -n "$hits" ] || exit 0
printf '%s\n' "$hits"
exit 1
