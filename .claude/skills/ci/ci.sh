#!/usr/bin/env bash
# Runs filmstrip's CI lane one Gradle call per step and prints a short digest.
#
#   .claude/skills/ci/ci.sh [--remote] [--hosts a,b] [--device] [--fast] [--rerun] [--dry] [--only step,step] [checkout]
#
# Steps keep going after a failure, so one digest shows every red step. The digest prints in step order.
# Every test step passes -Pmedia -Phardware -PpureEverywhere, the local lane. --fast drops them, the push lane.
# Without --remote every step runs on the Mac, one at a time, so ffmpeg and Apple never share VideoToolbox.
# --remote runs two chains at once. The mac chain (abi, wasm, js, macos, ios-boot, ios) stays on the Mac, and the
# remote chain sends the rest through scratchpad/remote/gradle.sh, to FILMSTRIP_REMOTE or cerebro.
# --hosts a,b implies --remote and splits the remote chain across the hosts by alternating steps, one chain per host.
# A host is cerebro, hoogflix or user@address. The device step always goes to the first host.
# --device adds connectedAndroidDeviceTest, which needs an emulator already booted where it runs.
# --rerun passes Gradle --rerun to each test step. It reruns only the last task of a call, so each test step has one.
# --dry prints each step's chain, host, properties and tasks, and runs nothing.
# checkout defaults to the main checkout, pass an integration worktree to test that instead.
# Logs land in $TMPDIR/filmstrip-ci/<checkout>/<step>.log.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(git -C "$here" rev-parse --show-toplevel)
# scratchpad is gitignored and lives only in the main checkout, so a copy of this script in a worktree reads it there.
gradle_remote="$(dirname "$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)")/scratchpad/remote/gradle.sh"
remote=0
device=0
rerun=0
fast=0
dry=0
hosts=""
only=""
checkout=""
while [ $# -gt 0 ]; do
  case $1 in
    --remote) remote=1 ;;
    --hosts) hosts=$2; remote=1; shift ;;
    --device) device=1 ;;
    --rerun) rerun=1 ;;
    --fast) fast=1 ;;
    --dry) dry=1 ;;
    --only) only=$2; shift ;;
    *) checkout=$1 ;;
  esac
  shift
done
checkout=$(cd "${checkout:-$repo}" && pwd)
logs="${TMPDIR:-/tmp}/filmstrip-ci/$(basename "$checkout")"
mkdir -p "$logs"

lane_props="-Pmedia -Phardware -PpureEverywhere"
[ "$fast" = 1 ] && lane_props=""

host_address() {
  case $1 in
    cerebro) echo user@10.0.0.5 ;;
    hoogflix) echo user@10.0.0.15 ;;
    *) echo "$1" ;;
  esac
}
remote_hosts=()
if [ -n "$hosts" ]; then
  IFS=',' read -r -a host_names <<<"$hosts"
  for h in "${host_names[@]}"; do remote_hosts+=("$(host_address "$h")"); done
else
  remote_hosts=("${FILMSTRIP_REMOTE:-user@10.0.0.5}")
fi

# name|gradle tasks|mac or any. wasm and js are mac because ChromeHeadless capture fails on cerebro.
steps=(
  "spotless|spotlessCheck|any"
  "build-logic|-p build-logic :convention:test|any"
  "abi|checkKotlinAbi|mac"
  "guards|checkForbiddenImports checkLayering checkKDocWidth|any"
  "lint|lint|any"
  "android-host|testAndroidHostTest|any"
  "jvm|jvmTest|any"
  "wasm|wasmJsBrowserTest|mac"
  "js|jsBrowserTest|mac"
  "macos|macosArm64Test|mac"
  "ios-boot|:filmstrip-player:bootIosSimulator|mac"
  "ios|iosSimulatorArm64Test|mac"
  "sample|:sample:webApp:wasmJsBrowserDistribution|any"
)
[ "$device" = 1 ] && steps+=("device|connectedAndroidDeviceTest|any")

# Logs can hold binary bytes. Without -a grep prints "Binary file matches" instead of the lines, and in a UTF-8
# locale BSD grep drops lines with invalid bytes and BSD sed stops at them.
log_grep() { LC_ALL=C grep -a "$@"; }
log_sed() { LC_ALL=C sed "$@"; }

# True when a step's Gradle tasks name a test task, in either case, since :convention:test is lowercase.
runs_tests() {
  [[ $(printf '%s' "$1" | tr '[:upper:]' '[:lower:]') == *test* ]]
}

# Each picked step gets a chain, a host and its properties, all in step order. Chains run at once, the steps in one
# chain run one after the other.
names=()
chains=()
targets=()
props=()
tasks_of=()
remote_turn=0
for step in "${steps[@]}"; do
  IFS='|' read -r name tasks where <<<"$step"
  if [ -n "$only" ] && [[ ",$only," != *",$name,"* ]]; then continue; fi
  if [ "$remote" = 0 ] || [ "$where" = mac ]; then
    chain=mac
    target=local
  else
    # The emulator is booted on one host, so the device step stays on the first.
    if [ "$name" = device ]; then
      slot=0
    else
      slot=$((remote_turn % ${#remote_hosts[@]}))
      remote_turn=$((remote_turn + 1))
    fi
    chain=remote
    [ "${#remote_hosts[@]}" -gt 1 ] && chain="remote$((slot + 1))"
    target=${remote_hosts[$slot]}
  fi
  step_props=""
  runs_tests "$tasks" && step_props=$lane_props
  # The properties go before the tasks, since --rerun applies to the task right before it.
  full="${step_props:+$step_props }$tasks"
  [ "$rerun" = 1 ] && runs_tests "$tasks" && full="$full --rerun"
  names+=("$name")
  chains+=("$chain")
  targets+=("$target")
  props+=("${step_props:--}")
  tasks_of+=("$full")
done
if [ "${#names[@]}" = 0 ]; then
  echo "no step picked by --only $only"
  exit 1
fi

if [ "$dry" = 1 ]; then
  printf '%-13s %-8s %-15s %-35s %s\n' step chain host properties tasks
  for i in "${!names[@]}"; do
    printf '%-13s %-8s %-15s %-35s %s\n' "${names[$i]}" "${chains[$i]}" "${targets[$i]}" "${props[$i]}" "${tasks_of[$i]#"${props[$i]} "}"
  done
  exit 0
fi

# Gradle only restores or skips a test task that passed, so the XML it left from that run is this step's result.
# Prints the result dir of every test task the log marks UP-TO-DATE or FROM-CACHE.
cached_dirs() {
  log_sed -nE 's/^> Task (:[^ ]+):([A-Za-z0-9]*[Tt]est) (UP-TO-DATE|FROM-CACHE)$/\1 \2/p' "$1" | while read -r project task; do
    dir="$checkout$(printf '%s' "$project" | tr ':' '/')/build/test-results/$task"
    [ -d "$dir" ] && printf '%s\n' "$dir"
  done
}

# Credits every result XML in the checkout newer than the step's marker to that step. That holds only while one
# local step runs at a time. Remote steps write their XML on the remote host, and every local step sits in the mac
# chain, which runs its steps one after the other. So no other step can write local XML while this one runs.
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

# Runs step i and writes its digest lines to $logs/<step>.digest.
run_step() {
  local i=$1
  local name=${names[$i]} tasks=${tasks_of[$i]} target=${targets[$i]}
  local log="$logs/$name.log" out="$logs/$name.digest"
  local start=$SECONDS at status counts cached
  at=$(date +%H:%M:%S)
  if [ "$target" != local ]; then
    # shellcheck disable=SC2086
    FILMSTRIP_REMOTE=$target "$gradle_remote" "$checkout" "ci-$(basename "$checkout")" $tasks >"$log" 2>&1
    status=$?
  else
    local marker="$logs/$name.marker"
    touch "$marker"
    # shellcheck disable=SC2086
    (cd "$checkout" && ./gradlew --console=plain $tasks </dev/null) >"$log" 2>&1
    status=$?
    summarise "$marker" "$log" >>"$log"
  fi
  local secs=$((SECONDS - start))
  local where="@$at ${chains[$i]}"
  counts=$(log_grep '^RESULT ' "$log" | log_grep -v 'none written' | log_sed -E 's/.*tests=([0-9]+) failed=([0-9]+) skipped=([0-9]+)/\1 \2 \3/' \
    | awk '{t+=$1; f+=$2; s+=$3} END {if (NR) printf "tests=%d failed=%d skipped=%d", t, f, s}')
  cached=$(log_grep -cE '^> Task :[^ ]+:[A-Za-z0-9]*[Tt]est (UP-TO-DATE|FROM-CACHE)$' "$log")
  [ "$cached" -gt 0 ] && counts="$counts cached=$cached"
  {
    # A test step that passes with no result XML, written or cached, ran no test, so it is not reported green.
    if [ "$status" = 0 ] && [ -z "$counts" ] && runs_tests "$tasks"; then
      echo "unknown $name ${secs}s $where no result XML written or cached  log: $log"
    elif [ "$status" = 0 ]; then
      echo "ok    $name ${secs}s $where $counts"
    else
      echo "FAIL  $name ${secs}s $where $counts  log: $log"
      {
        log_grep '^RESULT ' "$log" | log_grep -v 'failed=0 ' | log_sed 's/^RESULT //'
        log_grep -E '^> Task .* FAILED|FAILED$|^e: |What went wrong' -A2 "$log" | log_grep -v '^--$' | head -12
      } | log_sed 's/^/        /'
    fi
  } >"$out"
}

run_chain() {
  local chain=$1 i
  for i in "${!names[@]}"; do
    [ "${chains[$i]}" = "$chain" ] && run_step "$i"
  done
}

for i in "${!names[@]}"; do rm -f "$logs/${names[$i]}.digest"; done
picked_chains=$(printf '%s\n' ${chains[@]+"${chains[@]}"} | awk '!seen[$0]++')
pids=()
for chain in $picked_chains; do
  run_chain "$chain" &
  pids+=($!)
done
for pid in ${pids[@]+"${pids[@]}"}; do wait "$pid"; done

echo "--- ci digest @ $(git -C "$checkout" log -1 --format=%h) ($checkout) ---"
red=0
for i in "${!names[@]}"; do
  out="$logs/${names[$i]}.digest"
  if [ -f "$out" ]; then
    cat "$out"
    first=$(head -1 "$out")
    [[ $first == ok* ]] || red=$((red + 1))
  else
    echo "unknown ${names[$i]} wrote no digest"
    red=$((red + 1))
  fi
done
# exit is the number of FAIL and unknown steps.
echo "exit=$red"
