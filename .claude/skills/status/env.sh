#!/usr/bin/env bash
# Checks the machines a plan's units run on, so an owner chore shows up before a plan starts and not mid-plan.
# Prints one `ok|BLOCKED <what>: <fix>` line per check. Exit is the number of blocked checks.
set -u

blocked=0
check() {
  local what=$1 fix=$2
  shift 2
  if "$@" >/dev/null 2>&1; then
    echo "ok      $what"
  else
    echo "BLOCKED $what: $fix"
    blocked=$((blocked + 1))
  fi
}

check "xcode first launch" "owner runs \`sudo xcodebuild -runFirstLaunch\`" xcodebuild -checkFirstLaunchStatus
check "simctl" "owner opens Xcode once or reinstalls the simulator runtime" xcrun simctl list runtimes
check "cerebro ssh" "check cerebro is up and on the network" \
  ssh -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=5 -i "$HOME/.ssh/github.key" user@10.0.0.5 true

exit "$blocked"
