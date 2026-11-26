#!/usr/bin/env bash
# Checks failed-tests.py against the result XML in fixtures/failed-tests, laid out in a temporary root the way Gradle
# writes it. The fixtures sit outside any build directory so no result upload or slowest-tests.py walk picks them up.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/failed-tests.py"
source="$here/fixtures/failed-tests"
fixtures=$(mktemp -d)
long=$(mktemp -d)
summary=$(mktemp)
trap 'rm -rf "$fixtures" "$long" "$summary"' EXIT
failed=0

lay() {
  mkdir -p "$fixtures/$2"
  cp "$source/$1" "$fixtures/$2/TEST-$1"
}

lay kotlin-native.xml player/build/test-results/macosArm64Test
lay android.xml player/build/outputs/androidTest-results/connected
lay green.xml green/build/test-results/jvmTest
lay karma.xml player/build/test-results/karma

check() {
  local name=$1 actual=$2 expected=$3
  if [ "$actual" = "$expected" ]; then
    echo "ok    $name"
  else
    printf 'FAIL  %s\nexpected:\n%s\ngot:\n%s\n' "$name" "$expected" "$actual"
    failed=1
  fi
}

expected='FAIL io.filmstrip.player.AndroidEngineContractMediaTest.seeksExactly: java.lang.AssertionError: Seek landed at 0.966s, expected 1.0s
FAIL io.filmstrip.player.AppleShortClipSeamHardwareTest.cellTwoFilesWhoseTrimsMeet: Slot 12 held slot 11'

check "walks a root" "$(python3 "$script" "$fixtures")" "$expected"
check "reads paths from stdin" \
  "$(find "$fixtures" -name '*.xml' -print0 | python3 "$script" -)" "$expected"
check "green root" "$(python3 "$script" "$fixtures/green")" ""

mkdir -p "$long/build/test-results/jvmTest"
printf '<testsuite tests="1" failures="1"><testcase classname="A" name="b"><failure message="%s"/></testcase></testsuite>' \
  "$(printf 'x%.0s' $(seq 300))" >"$long/build/test-results/jvmTest/TEST-A.xml"
check "caps a long message" "$(python3 "$script" "$long" | wc -c | tr -d ' ')" "201"

GITHUB_STEP_SUMMARY="$summary" python3 "$script" "$fixtures" >/dev/null
check "writes the step summary" "$(cat "$summary")" "### Failed tests

- \`io.filmstrip.player.AndroidEngineContractMediaTest.seeksExactly\`: java.lang.AssertionError: Seek landed at 0.966s, expected 1.0s
- \`io.filmstrip.player.AppleShortClipSeamHardwareTest.cellTwoFilesWhoseTrimsMeet\`: Slot 12 held slot 11"

exit $failed
