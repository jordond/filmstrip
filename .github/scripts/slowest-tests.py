"""Prints the slowest test classes found in the JUnit XML under a checkout.

Reads every TEST-*.xml under a build/test-results or build/outputs/androidTest-results directory, sums the testcase
times per class and result directory, and prints the slowest. A missing or half-written file is skipped, so the step
never fails the job. Karma reports near-zero times for js and wasm, so those classes rank low whatever they cost.

Usage: slowest-tests.py [root] [count]
"""

import os
import sys
import xml.etree.ElementTree as ElementTree
from collections import defaultdict

RESULT_DIRS = ("/build/test-results/", "/build/outputs/androidTest-results/")
SKIPPED_DIRS = {".git", ".gradle", "node_modules", "kotlin-js-store"}


def result_files(root):
    for directory, children, files in os.walk(root):
        children[:] = [child for child in children if child not in SKIPPED_DIRS]
        relative = "/" + os.path.relpath(directory, root) + "/"
        if not any(marker in relative for marker in RESULT_DIRS):
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


def main():
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
