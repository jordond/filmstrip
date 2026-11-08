#!/usr/bin/env bash
# Runs filmstrip's CI lane one Gradle call per step and prints a short digest.
#
#   .claude/skills/ci/ci.sh [--plan [base]] [--remote] [--hosts a,b] [--device] [--fast] [--full] [--rerun] [--dry]
#                           [--only step,step] [checkout]
#
# Steps keep going after a failure, so one digest shows every red step. The digest prints in step order.
# Every test step passes -Pmedia -Phardware -PpureEverywhere, the local lane. --fast drops them, the push lane.
# The ios step runs only what only iOS runs, since the macos step already runs the shared classes on Kotlin/Native.
# It names only the modules printIosTestModules prints, so a module with nothing to run there is never linked.
# --full passes -PiosShared to the ios step and runs every module's iosSimulatorArm64Test, shared classes included.
# Without --remote every step runs on the Mac, one at a time, so ffmpeg and Apple never share VideoToolbox.
# --remote runs two chains at once. The mac chain (abi, wasm, js, macos, ios-boot, ios) stays on the Mac, and the
# remote chain sends the rest through scratchpad/remote/gradle.sh, to FILMSTRIP_REMOTE or cerebro.
# --hosts a,b implies --remote and splits the remote chain across two hosts, one chain each, in either order. The heavy
# host, cerebro when listed, runs jvm and device. The light host, hoogflix when listed, runs the short steps. jvm splits
# the ffmpeg suite 3:1 by -PtestShard, shards 1-3 on the heavy host in three test JVMs and shard 4 on the light one,
# and the light host also runs every other module's jvmTest as jvm-rest. A host is cerebro, hoogflix or user@address.
# gradle.sh runs each remote step detached and reconnects after a dropped ssh. A step it lost the host for is "lost".
# --device adds connectedAndroidDeviceTest, which needs an emulator already booted where it runs.
# --rerun passes Gradle --rerun to each test step. It reruns only the last task of a call, so each test step has one.
# --dry prints each step's chain, host, properties and tasks, and runs nothing.
# --plan [base] runs only the steps the checkout's changes since base owe, each scoped to the modules it reaches, and
# prints what each changed module owes first. base defaults to main and diffs base...HEAD, a range a..b diffs as given.
# plan.py has the rules. The module graph comes from printModuleGraph, cached until a build file changes.
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
ios_shared=0
dry=0
hosts=""
only=""
checkout=""
plan=0
plan_base=""
while [ $# -gt 0 ]; do
  case $1 in
    --plan)
      plan=1
      if [ $# -gt 1 ] && [[ $2 != --* ]] && git -C "$here" rev-parse -q --verify "${2%%..*}^{commit}" >/dev/null 2>&1; then
        plan_base=$2
        shift
      fi
      ;;
    --remote) remote=1 ;;
    --hosts) hosts=$2; remote=1; shift ;;
    --device) device=1 ;;
    --rerun) rerun=1 ;;
    --fast) fast=1 ;;
    --full) ios_shared=1 ;;
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
if [ "${#remote_hosts[@]}" -gt 2 ]; then
  echo "--hosts takes at most two hosts, one heavy and one light"
  exit 1
fi
# The heavy host is cerebro when listed, since it has the emulator and the most cores. The light host is the other one.
heavy=${remote_hosts[0]}
light=""
for h in "${remote_hosts[@]}"; do [ "$h" = "$(host_address cerebro)" ] && heavy=$h; done
for h in "${remote_hosts[@]}"; do [ "$h" != "$heavy" ] && light=$h; done

# name|gradle tasks|mac, heavy or any. wasm and js are mac because ChromeHeadless capture fails on cerebro. An any
# step goes to the light host when there are two.
steps=(
  "spotless|spotlessCheck|any"
  "build-logic|-p build-logic :convention:test|any"
  "abi|checkKotlinAbi|mac"
  "guards|checkForbiddenImports checkLayering checkKDocWidth|any"
  "lint|lint|any"
  "android-host|testAndroidHostTest|any"
  "jvm|jvmTest|heavy"
  "wasm|wasmJsBrowserTest|mac"
  "js|jsBrowserTest|mac"
  "macos|macosArm64Test|mac"
  "ios-boot|:filmstrip-player:bootIosSimulator|mac"
  "ios|iosSimulatorArm64Test|mac"
  "sample|:sample:demo:androidApp:assembleDebug|any"
)
[ "$device" = 1 ] && steps+=("device|connectedAndroidDeviceTest|heavy")

# Prints the path of printModuleGraph's output for the checkout, running it only when no copy matches its build files.
module_graph() {
  local key file paths=('*.gradle.kts' build-logic gradle/libs.versions.toml)
  key=$({
    git -C "$checkout" ls-files -s -- "${paths[@]}"
    git -C "$checkout" status --porcelain -- "${paths[@]}"
  } | shasum | cut -c1-16)
  file="$logs/module-graph-$key.txt"
  if [ ! -s "$file" ]; then
    if ! (cd "$checkout" && ./gradlew -q --console=plain printModuleGraph </dev/null) >"$file.tmp" 2>"$logs/module-graph.log"; then
      echo "printModuleGraph failed, see $logs/module-graph.log" >&2
      return 1
    fi
    mv "$file.tmp" "$file"
  fi
  echo "$file"
}

# Prints the path of printIosTestModules' output for the checkout, the modules whose iosSimulatorArm64Test runs a class
# under the iOS-only rule. It runs only when no copy matches the build files and the iOS-only test sources.
ios_modules() {
  local key file paths=('*.gradle.kts' build-logic gradle/libs.versions.toml '*/src/iosTest/*'
    '*/src/iosSimulatorArm64Test/*' '*/src/appleHostTest/*')
  key=$({
    git -C "$checkout" ls-files -s -- "${paths[@]}"
    git -C "$checkout" status --porcelain -- "${paths[@]}"
  } | shasum | cut -c1-16)
  file="$logs/ios-modules-$key.txt"
  if [ ! -s "$file" ]; then
    if ! (cd "$checkout" && ./gradlew -q --console=plain printIosTestModules </dev/null) >"$file.tmp" 2>"$logs/ios-modules.log"; then
      echo "printIosTestModules failed, see $logs/ios-modules.log" >&2
      return 1
    fi
    sort -u "$file.tmp" | grep -v '^w:' >"$file"
    rm -f "$file.tmp"
  fi
  echo "$file"
}

if [ "$plan" = 1 ]; then
  range=${plan_base:-main}
  [[ $range == *..* ]] || range="$range...HEAD"
  graph=$(module_graph) || exit 1
  git -C "$checkout" diff --name-only "$range" >"$logs/plan.changed" || exit 1
  python3 "$here/plan.py" "$graph" "$logs/plan.changed" >"$logs/plan.out" || exit 1
  echo "--- plan $range ---"
  sed '/^steps$/,$d' "$logs/plan.out"
  owed_steps=$(sed '1,/^steps$/d' "$logs/plan.out")
  if [ "$device" = 0 ] && printf '%s\n' "$owed_steps" | grep -q '^device|'; then
    echo "device owed, add --device to run it"
  fi
  picked=()
  for step in "${steps[@]}"; do
    IFS='|' read -r name tasks where <<<"$step"
    line=$(printf '%s\n' "$owed_steps" | grep -m1 "^$name|")
    [ -z "$line" ] && continue
    scoped=${line#*|}
    picked+=("$name|${scoped:-$tasks}|$where")
  done
  steps=(${picked[@]+"${picked[@]}"})
  echo
fi

# Under the iOS-only rule the ios step keeps only the modules ios_modules prints, and drops out with ios-boot when a
# scoped ios step names none of them.
picks_ios=0
for step in ${steps[@]+"${steps[@]}"}; do
  [[ $step == ios\|* ]] && { [ -z "$only" ] || [[ ",$only," == *",ios,"* ]]; } && picks_ios=1
done
if [ "$ios_shared" = 0 ] && [ "$picks_ios" = 1 ]; then
  modules_file=$(ios_modules) || exit 1
  kept=()
  for step in "${steps[@]}"; do
    IFS='|' read -r name tasks where <<<"$step"
    if [ "$name" = ios ]; then
      scoped=""
      while IFS= read -r module; do
        task=":$module:iosSimulatorArm64Test"
        if [ "$tasks" = iosSimulatorArm64Test ] || [[ " $tasks " == *" $task "* ]]; then scoped="${scoped:+$scoped }$task"; fi
      done <"$modules_file"
      [ -z "$scoped" ] && continue
      step="ios|$scoped|$where"
    fi
    kept+=("$step")
  done
  if ! printf '%s\n' "${kept[@]}" | grep -q '^ios|'; then
    dropped=()
    for step in ${kept[@]+"${kept[@]}"}; do [[ $step == ios-boot\|* ]] || dropped+=("$step"); done
    kept=(${dropped[@]+"${dropped[@]}"})
  fi
  steps=(${kept[@]+"${kept[@]}"})
fi

# With two hosts the jvm step splits into the ffmpeg suite's shards 1-3 on the heavy host, shard 4 on the light one,
# and jvm-rest for every other module's jvm tasks. The heavy host's 24 threads take three test JVMs, more
# oversubscribes it. A scoped jvm step without the ffmpeg suite stays whole.
ffmpeg_jvm=:filmstrip-transform-ffmpeg:jvmTest
if [ "$remote" = 1 ] && [ -n "$light" ]; then
  split=()
  for step in ${steps[@]+"${steps[@]}"}; do
    IFS='|' read -r name tasks where <<<"$step"
    if [ "$name" != jvm ]; then
      split+=("$step")
      continue
    fi
    if [ "$tasks" = jvmTest ]; then
      rest="-x $ffmpeg_jvm jvmTest"
    else
      rest=$(printf '%s\n' $tasks | grep -vxF "$ffmpeg_jvm" | tr '\n' ' ' | sed 's/ $//')
      if [ "$rest" = "$tasks" ]; then
        split+=("$step")
        continue
      fi
    fi
    split+=("jvm|-PtestShard=1-3/4 -PtestForks=3 $ffmpeg_jvm|heavy" "jvm-shard|-PtestShard=4/4 $ffmpeg_jvm|light")
    [ -n "$rest" ] && split+=("jvm-rest|$rest|light")
  done
  steps=(${split[@]+"${split[@]}"})
fi

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
for step in ${steps[@]+"${steps[@]}"}; do
  IFS='|' read -r name tasks where <<<"$step"
  # --only jvm picks every part of a split jvm step.
  if [ -n "$only" ] && [[ ",$only," != *",$name,"* ]] && [[ $name != jvm-* || ",$only," != *",jvm,"* ]]; then
    continue
  fi
  if [ "$remote" = 0 ] || [ "$where" = mac ]; then
    chain=mac
    target=local
  elif [ -z "$light" ]; then
    chain=remote
    target=$heavy
  elif [ "$where" = heavy ]; then
    chain=heavy
    target=$heavy
  else
    chain=light
    target=$light
  fi
  step_props=""
  runs_tests "$tasks" && step_props=$lane_props
  [ "$ios_shared" = 1 ] && [ "$name" = ios ] && step_props="${step_props:+$step_props }-PiosShared"
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
  printf '%-13s %-8s %-15s %-47s %s\n' step chain host properties tasks
  for i in "${!names[@]}"; do
    printf '%-13s %-8s %-15s %-47s %s\n' "${names[$i]}" "${chains[$i]}" "${targets[$i]}" "${props[$i]}" "${tasks_of[$i]#"${props[$i]} "}"
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
    # gradle.sh exits 255 only when it lost the host for its whole reconnect window, the run may still be going.
    if [ "$status" = 255 ] && [ "$target" != local ]; then
      echo "lost  $name ${secs}s $where ssh to $target dropped past every reconnect  log: $log"
    elif [ "$status" = 0 ] && [ -z "$counts" ] && runs_tests "$tasks"; then
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
# exit is the number of FAIL, lost and unknown steps.
echo "exit=$red"
