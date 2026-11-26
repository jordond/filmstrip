"""Prints one line per failed or errored test found in the JUnit XML under a checkout.

Reads every TEST-*.xml under a build/test-results or build/outputs/androidTest-results directory and prints
`FAIL <class>.<name>: <message>` for each testcase holding a failure or an error. The message is the first line of
the failure's message attribute, or of its body when the attribute is missing, and the whole line stops at 200
characters. The Karma copies under test-results/karma repeat the browser tests Gradle already wrote, so they are
left out. Android writes an assumption skip as a failure, so one is not printed. A missing or half-written file is
skipped, so the step never fails the job.

With GITHUB_STEP_SUMMARY set the same lines are appended to the job summary as a list. Given `-` in place of a root
it reads the result files to check from stdin, separated by NUL or newline.

Usage: failed-tests.py [root | -]
"""

import os
import re
import sys
import xml.etree.ElementTree as ElementTree

RESULT_DIRS = ("/build/test-results/", "/build/outputs/androidTest-results/")
SKIPPED_DIRS = {".git", ".gradle", "node_modules", "kotlin-js-store"}
KARMA_DIR = "/test-results/karma/"
TASK_PREFIX = re.compile(r"^[a-z][A-Za-z0-9]*Test\.")
ASSUMPTION = re.compile(r"^[\w.$]*AssumptionViolatedException\b")
LINE_LIMIT = 200


def result_files(root):
    for directory, children, files in os.walk(root):
        children[:] = [child for child in children if child not in SKIPPED_DIRS]
        relative = "/" + os.path.relpath(directory, root) + "/"
        if not any(marker in relative for marker in RESULT_DIRS) or KARMA_DIR in relative:
            continue
        for name in files:
            if name.startswith("TEST-") and name.endswith(".xml"):
                yield os.path.join(directory, name)


def listed_files():
    for path in re.split(r"[\0\n]", sys.stdin.read()):
        if path and KARMA_DIR not in path:
            yield path


def first_line(text):
    for line in (text or "").splitlines():
        if line.strip():
            return line.strip()
    return ""


def failures(path):
    try:
        cases = list(ElementTree.parse(path).getroot().iter("testcase"))
    except (ElementTree.ParseError, OSError) as error:
        print(f"skipping {path}: {error}", file=sys.stderr)
        return
    for case in cases:
        for problem in [*case.findall("failure"), *case.findall("error")]:
            body = first_line(problem.text)
            if ASSUMPTION.match(problem.get("type") or "") or ASSUMPTION.match(body):
                continue
            name = TASK_PREFIX.sub("", case.get("classname", "?")) + "." + case.get("name", "?")
            yield name, first_line(problem.get("message")) or body


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "."
    paths = listed_files() if source == "-" else result_files(source)
    found = sorted({failure for path in paths for failure in failures(path)})
    for name, message in found:
        print(f"FAIL {name}: {message}"[:LINE_LIMIT])
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and found:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("### Failed tests\n\n")
            for name, message in found:
                out.write(f"- `{name}`: {message}"[:LINE_LIMIT] + "\n")


if __name__ == "__main__":
    main()
