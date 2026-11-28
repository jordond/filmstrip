"""Prints one row per Apple cell per run from unpacked runner probe and test result artifacts.

Each argument is a directory holding one run's artifacts, as `gh run download <run> -D <dir>` leaves them: a
`media-probe-<cell>` directory with `probe.jsonl`, a `media-results-<cell>` directory of JUnit XML and, for the ios
player cell, a directory with `simulator.log.gz`. The `media-` prefix is optional.

A row holds each probe figure before and after the tests in milliseconds, the cell's class time ratio, the number of
failed tests and the longest Metal compile the test process asked for. The ratio is the median, over every class that
takes two seconds or more, of the class's time against its median time across all the runs given, so 1.0 is a normal
run and 2.0 a run where the tests took twice as long.

Usage: runner-probe-report.py run-dir...
"""

import glob
import gzip
import json
import os
import re
import statistics
import sys
import xml.etree.ElementTree as ElementTree
from collections import defaultdict

FIGURES = ("cpu", "disk", "swift", "metal", "encode", "simctl")
CLASS_FLOOR = 2.0
METAL_TIME = re.compile(r"ParentProcessName=test[._]kexe.*Time ([\d.]+) ms")


def cells(run):
    for path in sorted(glob.glob(os.path.join(run, "*probe-*"))):
        yield os.path.basename(path).split("probe-", 1)[1], path


def probe_lines(path):
    lines = {}
    try:
        with open(os.path.join(path, "probe.jsonl")) as handle:
            for text in handle:
                line = json.loads(text)
                lines[line["label"]] = line
    except (OSError, ValueError, KeyError):
        pass
    return lines


def class_times(run, cell):
    times, failed = defaultdict(float), 0
    for root in glob.glob(os.path.join(run, "*results-" + cell)):
        for path in glob.glob(os.path.join(root, "**", "TEST-*.xml"), recursive=True):
            try:
                suite = ElementTree.parse(path).getroot()
            except (OSError, ElementTree.ParseError):
                continue
            for case in suite.iter("testcase"):
                times[case.get("classname", "")] += float(case.get("time") or 0)
                failed += sum(1 for child in case if child.tag in ("failure", "error"))
    return times, failed


def metal_peak(run):
    peak = None
    for path in glob.glob(os.path.join(run, "**", "simulator.log.gz"), recursive=True):
        try:
            with gzip.open(path, "rt", errors="replace") as handle:
                for text in handle:
                    match = METAL_TIME.search(text)
                    if match:
                        peak = max(peak or 0.0, float(match.group(1)))
        except (OSError, EOFError):
            pass
    return peak


def main(runs):
    rows, samples = [], defaultdict(list)
    for run in runs:
        for cell, path in cells(run):
            times, failed = class_times(run, cell)
            for name, seconds in times.items():
                samples[cell, name].append(seconds)
            peak = metal_peak(run) if cell == "ios-player" else None
            rows.append((os.path.basename(os.path.normpath(run)), cell, probe_lines(path), times, failed, peak))
    baseline = {key: statistics.median(values) for key, values in samples.items()}

    print("run          cell         red ratio " + " ".join(f"{name:>13}" for name in FIGURES) + "  kexe-metal")
    for run, cell, lines, times, failed, peak in rows:
        ratios = [
            seconds / baseline[cell, name] for name, seconds in times.items() if baseline[cell, name] >= CLASS_FLOOR
        ]
        ratio = f"{statistics.median(ratios):5.2f}" if ratios else "    -"
        figures = " ".join(
            f"{figure(lines, 'before', name):>6}/{figure(lines, 'after', name):<6}" for name in FIGURES
        )
        metal = f"{peak:10.0f}" if peak is not None else "         -"
        print(f"{run:<12} {cell:<12} {failed:>3} {ratio} {figures}  {metal}")


def figure(lines, label, name):
    value = lines.get(label, {}).get(name)
    return "-" if value is None else str(value)


if __name__ == "__main__":
    main(sys.argv[1:])
