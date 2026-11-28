#!/usr/bin/env bash
# Runs runner-probe.sh twice in a temporary directory and checks it wrote two JSON lines, each with its label and
# with every figure a number or null, and that the figures any Mac can take came back as numbers.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

(cd "$work" && bash "$here/runner-probe.sh" before > /dev/null && bash "$here/runner-probe.sh" after > /dev/null)

python3 - "$work/build/runner-probe/probe.jsonl" << 'PY'
import json
import sys

lines = [json.loads(line) for line in open(sys.argv[1])]
failed = False


def check(name, passed):
    global failed
    print(("ok    " if passed else "FAIL  ") + name)
    failed = failed or not passed


check("two lines", len(lines) == 2)
check("labels in order", [line["label"] for line in lines] == ["before", "after"])
figures = ["cpu", "disk", "swift", "metal", "encode", "simctl"]
for line in lines:
    label = line["label"]
    check(f"{label} has every figure", all(name in line for name in figures))
    check(f"{label} figures are whole milliseconds or null",
          all(line[name] is None or (isinstance(line[name], int) and line[name] >= 0) for name in figures))
    check(f"{label} cpu and disk are measured", isinstance(line["cpu"], int) and isinstance(line["disk"], int))
    if sys.platform == "darwin":
        check(f"{label} metal and encode are measured",
              isinstance(line["metal"], int) and isinstance(line["encode"], int))
sys.exit(1 if failed else 0)
PY
