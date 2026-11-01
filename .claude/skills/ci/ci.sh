#!/usr/bin/env bash
# Runs filmstrip's CI lane one Gradle call per step and prints a short digest.
#
#   .claude/skills/ci/ci.sh [--remote] [--device] [--rerun] [--only step,step] [checkout]
#
# Steps run in order and keep going after a failure, so one digest shows every red step.
# --remote sends the steps that can run on Linux to cerebro through scratchpad/remote/gradle.sh.
# Apple suites and the ABI check always stay on the Mac.
# --device adds connectedAndroidDeviceTest, which needs an emulator already booted where it runs.
# --rerun passes Gradle --rerun to each test step. It reruns only the last task of a call, so each test step has one.
# checkout defaults to the main checkout, pass an integration worktree to test that instead.
# Logs land in $TMPDIR/filmstrip-ci/<step>.log.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(git -C "$here" rev-parse --show-toplevel)
remote=0
device=0
rerun=0
only=""
checkout=""
while [ $# -gt 0 ]; do
  case $1 in
    --remote) remote=1 ;;
    --device) device=1 ;;
    --rerun) rerun=1 ;;
    --only) only=$2; shift ;;
    *) checkout=$1 ;;
  esac
  shift
done
checkout=$(cd "${checkout:-$repo}" && pwd)
logs="${TMPDIR:-/tmp}/filmstrip-ci"
mkdir -p "$logs"

# name|gradle tasks|mac or any
steps=(
  "spotless|spotlessCheck|any"
  "abi|checkKotlinAbi|mac"
  "guards|checkForbiddenImports checkLayering|any"
  "lint|lint|any"
  "android-host|testAndroidHostTest|any"
  "jvm|jvmTest|any"
  "wasm|wasmJsBrowserTest|any"
  "js|jsBrowserTest|any"
  "macos|macosArm64Test|mac"
  "ios-boot|:filmstrip-player:bootIosSimulator|mac"
  "ios|iosSimulatorArm64Test|mac"
  "sample|:sample:androidApp:assembleDebug|any"
)
[ "$device" = 1 ] && steps+=("device|connectedAndroidDeviceTest|any")

# Gradle only restores or skips a test task that passed, so the XML it left from that run is this step's result.
# Prints the result dir of every test task the log marks UP-TO-DATE or FROM-CACHE.
cached_dirs() {
  sed -nE 's/^> Task (:[^ ]+):([A-Za-z0-9]*Test) (UP-TO-DATE|FROM-CACHE)$/\1 \2/p' "$1" | while read -r project task; do
    dir="$checkout$(printf '%s' "$project" | tr ':' '/')/build/test-results/$task"
    [ -d "$dir" ] && printf '%s\n' "$dir"
  done
}

summarise() {
  local marker=$1 log=$2
  {
    find "$checkout" -path '*/build/*' -name '*.xml' -newer "$marker" \( -path '*test-results*' -o -path '*androidTest-results*' \)
    cached_dirs "$log" | while IFS= read -r dir; do find "$dir" -name '*.xml'; done
  } | sort -u | tr '\n' '\0' | xargs -0 grep -l --null '<testsuite' 2>/dev/null | python3 -c '
import sys, re, os, collections
root = sys.argv[1]
agg = collections.defaultdict(lambda: [0, 0, 0])
for path in [p for p in sys.stdin.read().split("\0") if p]:
    text = open(path, encoding="utf-8", errors="replace").read()
    rel = os.path.relpath(path, root)
    key = rel.split("/build/")[0] + " " + rel.split("/build/")[1].rsplit("/", 1)[0]
    for m in re.finditer(r"<testsuite\b([^>]*)>", text):
        a = dict(re.findall(r"(\w+)=\"([^\"]*)\"", m.group(1)))
        agg[key][0] += int(a.get("tests", 0))
        agg[key][1] += int(a.get("failures", 0)) + int(a.get("errors", 0))
        agg[key][2] += int(a.get("skipped", 0))
    # Android writes an assumption skip as a <failure> whose text opens on the exception, so it is moved to skipped.
    assumed = sum(
        1 for attrs, body in re.findall(r"<failure\b([^>]*)>([^<]*)", text)
        if re.search(r"\btype=\"[\w.$]*AssumptionViolatedException\"", attrs) or re.match(r"\s*[\w.$]*AssumptionViolatedException\b", body)
    )
    agg[key][1] -= assumed
    agg[key][2] += assumed
for k, (t, f, s) in sorted(agg.items()):
    print(f"RESULT {k}: tests={t} failed={f} skipped={s}")
if not agg:
    print("RESULT none written by this call")
' "$checkout"
}

digest=()
red=0
for step in "${steps[@]}"; do
  IFS='|' read -r name tasks where <<<"$step"
  if [ -n "$only" ] && [[ ",$only," != *",$name,"* ]]; then continue; fi
  log="$logs/$name.log"
  [ "$rerun" = 1 ] && [[ $tasks == *Test* ]] && tasks="$tasks --rerun"
  start=$SECONDS
  if [ "$remote" = 1 ] && [ "$where" = any ]; then
    # shellcheck disable=SC2086
    "$repo/scratchpad/remote/gradle.sh" "$checkout" "ci-$(basename "$checkout")" $tasks >"$log" 2>&1
    status=$?
  else
    marker="$logs/$name.marker"
    touch "$marker"
    # shellcheck disable=SC2086
    (cd "$checkout" && ./gradlew --console=plain $tasks </dev/null) >"$log" 2>&1
    status=$?
    summarise "$marker" "$log" >>"$log"
  fi
  secs=$((SECONDS - start))
  counts=$(grep '^RESULT ' "$log" | grep -v 'none written' | sed -E 's/.*tests=([0-9]+) failed=([0-9]+) skipped=([0-9]+)/\1 \2 \3/' \
    | awk '{t+=$1; f+=$2; s+=$3} END {if (NR) printf "tests=%d failed=%d skipped=%d", t, f, s}')
  cached=$(grep -cE '^> Task :[^ ]+:[A-Za-z0-9]*Test (UP-TO-DATE|FROM-CACHE)$' "$log")
  [ "$cached" -gt 0 ] && counts="$counts cached=$cached"
  # A test step that passes with no result XML, written or cached, ran no test, so it is not reported green.
  if [ "$status" = 0 ] && [ -z "$counts" ] && [[ $tasks == *Test* ]]; then
    red=$((red + 1))
    digest+=("unknown $name ${secs}s no result XML written or cached  log: $log")
  elif [ "$status" = 0 ]; then
    digest+=("ok    $name ${secs}s $counts")
  else
    red=$((red + 1))
    digest+=("FAIL  $name ${secs}s $counts  log: $log")
    while IFS= read -r line; do digest+=("        $line"); done < <(
      grep '^RESULT ' "$log" | grep -v 'failed=0 ' | sed 's/^RESULT //'
      grep -E '^> Task .* FAILED|FAILED$|^e: |What went wrong' -A2 "$log" | grep -v '^--$' | head -12
    )
  fi
done

echo "--- ci digest @ $(git -C "$checkout" log -1 --format=%h) ($checkout) ---"
printf '%s\n' ${digest[@]+"${digest[@]}"}
# exit is the number of FAIL and unknown steps.
echo "exit=$red"
