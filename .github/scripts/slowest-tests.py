"""Prints the slowest test classes found in the JUnit XML under a checkout.

Reads every TEST-*.xml under a build/test-results or build/outputs/androidTest-results directory, sums the testcase
times per class and result directory, and prints the slowest. A missing or half-written file is skipped, so the step
never fails the job. Gradle's own js and wasm results carry zero times, so those are skipped in favour of the
build/test-results/karma files the shared Karma config writes with the times Mocha measured.

With --weights it prints the shard weights file instead, gradle/test-weights.txt, one "class seconds" line per class
with the longest time any result directory gave it. Each source is a directory of result XML, like an unpacked media
lane artifact, or a saved log holding this script's own table, like `gh run view <id> --log`. Classes under a tenth of
a second are left out, since a shard puts a class with no weight by its name instead.

The sources after `--scope name` also write `@name class seconds` lines, the longest time those sources alone gave
each class. A Gradle call given -PtestWeights=name packs by only those lines, across all its test tasks at once.
`--keep file` reads an earlier weights file and carries over its unscoped lines for classes no source timed, like
the hardware classes only a local lane runs.

Usage: slowest-tests.py [root] [count]
       slowest-tests.py --weights [--keep file] source... [--scope name source...]...
"""

import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from collections import defaultdict

RESULT_DIRS = ("/build/test-results/", "/build/outputs/androidTest-results/")
SKIPPED_DIRS = {".git", ".gradle", "node_modules", "kotlin-js-store"}
ZERO_TIME_DIRS = ("/test-results/jsBrowserTest/", "/test-results/wasmJsBrowserTest/")
TABLE_LINE = re.compile(r"^\s*(\d+\.\d+)  (\S+)  \((\S+)\)\s*$")
TIMESTAMP = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z ")
TASK_PREFIX = re.compile(r"^[a-z][A-Za-z0-9]*Test\.")
WEIGHT_FLOOR = 0.1
WEIGHTS_HEADER = """\
# Shard weights, test class and seconds. Written by .github/scripts/slowest-tests.py --weights.
#
# -PtestShard=i/n packs the classes it finds here longest first and puts the rest by a hash of the name, so a stale
# or missing line only costs balance. To regenerate from a media lane run:
#
#   gh run download <run> -D <dir>
#   python3 .github/scripts/slowest-tests.py --weights --keep gradle/test-weights.txt <dir> \\
#     --scope ffmpeg-8 <dir>/media-results-ffmpeg-8-* --scope ffmpeg-9 <dir>/media-results-ffmpeg-9-* > new.txt
#   mv new.txt gradle/test-weights.txt
#
# An @scope line is read only by a call given -PtestWeights=scope, which packs every class of its scope as one, so
# the ffmpeg cells balance across all the modules a call runs. Each ffmpeg major times its classes differently, so
# each has its own scope.
"""


def result_files(root):
    for directory, children, files in os.walk(root):
        children[:] = [child for child in children if child not in SKIPPED_DIRS]
        relative = "/" + os.path.relpath(directory, root) + "/"
        if not any(marker in relative for marker in RESULT_DIRS):
            continue
        if any(marker in relative for marker in ZERO_TIME_DIRS):
            continue
        for name in files:
            if name.startswith("TEST-") and name.endswith(".xml"):
                yield os.path.join(directory, name)


def class_times(root):
    totals = defaultdict(float)
    for path in result_files(root):
        target = os.path.relpath(os.path.dirname(path), root)
        try:
            for case in ElementTree.parse(path).getroot().iter("testcase"):
                totals[(case.get("classname", "?"), target)] += float(case.get("time") or 0)
        except (ElementTree.ParseError, ValueError, OSError) as error:
            print(f"skipping {path}: {error}", file=sys.stderr)
    return totals


def logged_times(path):
    totals = {}
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            # A GitHub log line opens with the job and the step, each before a tab, then a timestamp.
            match = TABLE_LINE.match(TIMESTAMP.sub("", line.split("\t")[-1]))
            if match:
                totals[(match.group(2), match.group(3))] = float(match.group(1))
    return totals


def kept_weights(path):
    kept = {}
    with open(path, encoding="utf-8") as lines:
        for line in lines:
            parts = line.split()
            if len(parts) == 2 and not line.startswith("#"):
                kept[parts[0]] = float(parts[1])
    return kept


def weights(arguments):
    longest = defaultdict(float)
    scoped = defaultdict(lambda: defaultdict(float))
    kept = {}
    scope = None
    arguments = iter(arguments)
    for source in arguments:
        if source == "--scope":
            scope = next(arguments)
            continue
        if source == "--keep":
            kept.update(kept_weights(next(arguments)))
            continue
        times = class_times(source) if os.path.isdir(source) else logged_times(source)
        for (name, _), seconds in times.items():
            # Kotlin/Native results can lead the class with the test task, which no filter sees.
            name = TASK_PREFIX.sub("", name)
            longest[name] = max(longest[name], seconds)
            if scope:
                scoped[scope][name] = max(scoped[scope][name], seconds)
    for name, seconds in kept.items():
        longest.setdefault(name, seconds)
    print(WEIGHTS_HEADER, end="")
    for name, seconds in sorted(longest.items()):
        if seconds >= WEIGHT_FLOOR:
            print(f"{name} {seconds:.2f}")
    for scope, times in sorted(scoped.items()):
        for name, seconds in sorted(times.items()):
            if seconds >= WEIGHT_FLOOR:
                print(f"@{scope} {name} {seconds:.2f}")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--weights":
        weights(sys.argv[2:])
        return
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    totals = class_times(root)
    if not totals:
        print("no test results found")
        return
    slowest = sorted(totals.items(), key=lambda item: item[1], reverse=True)[:count]
    print(f"{'seconds':>9}  class  (results)")
    for (name, target), seconds in slowest:
        print(f"{seconds:9.2f}  {name}  ({target})")


if __name__ == "__main__":
    main()
