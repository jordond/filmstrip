"""Works out which CI steps a change owes, and the tasks of each, from the module graph and a list of changed files.

Usage: plan.py <graph file> <changed files file>

The graph is `./gradlew -q printModuleGraph` output. Each changed file maps to its module and the source set whose
Kotlin directory holds it, or the `src/<set>/` it sits under, or else the whole module. A main change reaches every
test compilation of the same targets in the module and in each module that depends on it, through main dependencies
for as far as they go and one test dependency at the end. A test change reaches only its own compilations.

A change under build-logic, gradle, kotlin-js-store or to a root build file owes every step, unscoped. The weights
file only moves shard balance, so it owes nothing.

Prints a table of what each changed module owes, then one `step|tasks` line per owed step after a `steps` line.
Empty tasks mean the step runs as the full lane does.
"""

import collections
import sys

FORCE_PREFIXES = ("build-logic/", "gradle/", "kotlin-js-store/")
FORCE_FILES = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
EXEMPT_FILES = {"gradle/test-weights.txt"}
ALL_STEPS = ["spotless", "build-logic", "abi", "guards", "lint", "android-host", "jvm", "wasm", "js", "macos",
             "ios-boot", "ios", "sample", "device"]

# A test compilation, by target and compilation name, and the step and task that run it.
TEST_TASKS = {
    ("jvm", "test"): ("jvm", "jvmTest"),
    ("android", "hostTest"): ("android-host", "testAndroidHostTest"),
    ("android", "deviceTest"): ("device", "connectedAndroidDeviceTest"),
    ("js", "test"): ("js", "jsBrowserTest"),
    ("wasmJs", "test"): ("wasm", "wasmJsBrowserTest"),
    ("macosArm64", "test"): ("macos", "macosArm64Test"),
    ("iosSimulatorArm64", "test"): ("ios", "iosSimulatorArm64Test"),
}


class Graph:
    def __init__(self, path):
        self.dirs = {}
        self.main_dependents = collections.defaultdict(set)
        self.test_dependents = collections.defaultdict(set)
        self.source_sets = collections.defaultdict(dict)
        self.source_dirs = []
        for line in open(path, encoding="utf-8"):
            parts = line.split()
            if not parts:
                continue
            if parts[0] == "project":
                self.dirs[parts[1]] = parts[2]
            elif parts[0] == "dep":
                dependents = self.main_dependents if parts[2] == "main" else self.test_dependents
                dependents[parts[3]].add(parts[1])
            elif parts[0] == "sourceSet":
                module, name = parts[1], parts[2]
                feeds = set() if parts[3] == "-" else {tuple(feed.split("/", 1)) for feed in parts[3].split(",")}
                self.source_sets[module][name] = feeds
                if parts[4] != "-":
                    for directory in parts[4].split(","):
                        self.source_dirs.append((directory.rstrip("/") + "/", module, name))
        self.source_dirs.sort(key=lambda entry: len(entry[0]), reverse=True)

    def module_of(self, path):
        owners = [(len(d), m) for m, d in self.dirs.items() if d != "." and path.startswith(d.rstrip("/") + "/")]
        return max(owners)[1] if owners else None

    def targets(self, module):
        return {target for feeds in self.source_sets[module].values() for target, _ in feeds if target != "metadata"}

    def test_compilations(self, module):
        return {feed for feeds in self.source_sets[module].values() for feed in feeds if feed in TEST_TASKS}


def classify(graph, path):
    """The module a file belongs to and the compilations its change reaches there, or None outside every module."""
    # A directory can sit in more than one source set, like a contract folder a device test set adds.
    hits = [(module, name) for directory, module, name in graph.source_dirs if path.startswith(directory)]
    if hits:
        module = hits[0][0]
        feeds = set()
        for owner, name in hits:
            if owner == module:
                feeds |= graph.source_sets[module][name]
        return module, feeds
    module = graph.module_of(path)
    if module is None:
        return None
    directory = graph.dirs[module].rstrip("/") + "/"
    rest = path[len(directory):].split("/")
    if len(rest) > 2 and rest[0] == "src" and rest[1] in graph.source_sets[module]:
        return module, graph.source_sets[module][rest[1]]
    # A build script or anything else in the module reaches every compilation it has.
    return module, {(target, "main") for target in graph.targets(module)} | graph.test_compilations(module)


def owed(graph, changed):
    """Maps each module to the test compilations it owes, and each changed module to its kinds of change."""
    owes = collections.defaultdict(set)
    kinds = collections.defaultdict(set)
    for path in changed:
        hit = classify(graph, path)
        if hit is None:
            continue
        module, feeds = hit
        main_targets = {target for target, compilation in feeds if compilation == "main" and target != "metadata"}
        tests = {feed for feed in feeds if feed in TEST_TASKS}
        kinds[module].add("main" if main_targets else "test")
        owes[module] |= tests
        if main_targets:
            spread(graph, module, main_targets, owes)
    return owes, kinds


def spread(graph, module, targets, owes):
    queue = collections.deque([(module, frozenset(targets))])
    seen = set()
    while queue:
        current, reach = queue.popleft()
        if (current, reach) in seen or not reach:
            continue
        seen.add((current, reach))
        owes[current] |= {feed for feed in graph.test_compilations(current) if feed[0] in reach}
        for dependent in graph.main_dependents[current]:
            queue.append((dependent, frozenset(reach & graph.targets(dependent))))
        for dependent in graph.test_dependents[current]:
            owes[dependent] |= {feed for feed in graph.test_compilations(dependent) if feed[0] in reach}


def forced(path):
    if path in EXEMPT_FILES:
        return False
    return path in FORCE_FILES or path.startswith(FORCE_PREFIXES)


def main():
    graph = Graph(sys.argv[1])
    changed = [line.strip() for line in open(sys.argv[2], encoding="utf-8") if line.strip()]
    forcing = [path for path in changed if forced(path)]
    if forcing:
        print(f"{len(changed)} files changed, {forcing[0]} forces every step")
        print("steps")
        for step in ALL_STEPS:
            print(f"{step}|")
        return

    owes, kinds = owed(graph, changed)
    tasks = collections.defaultdict(list)
    for module in sorted(owes):
        for feed in sorted(owes[module]):
            step, task = TEST_TASKS[feed]
            tasks[step].append(f"{module}:{task}")

    print(f"{len(changed)} files changed, {len(kinds)} modules touched, {len(owes)} owe a suite")
    print(f"{'module':<36} {'change':<10} suites")
    for module in sorted(set(owes) | set(kinds)):
        suites = sorted({TEST_TASKS[feed][0] for feed in owes[module]})
        change = "+".join(sorted(kinds[module])) or "reached"
        print(f"{module:<36} {change:<10} {' '.join(suites) or '-'}")

    steps = {"spotless": "", "guards": ""}
    if any("main" in kind for kind in kinds.values()):
        steps["abi"] = ""
    if tasks.get("android-host") or tasks.get("device"):
        steps["lint"] = ""
    for step, step_tasks in tasks.items():
        steps[step] = " ".join(step_tasks)
    if "ios" in steps:
        steps["ios-boot"] = ""
    if any(module.startswith(":sample") for module in owes) or any(m.startswith(":sample") for m in kinds):
        steps["sample"] = ""
    print("steps")
    for step in ALL_STEPS:
        if step in steps:
            print(f"{step}|{steps[step]}")


if __name__ == "__main__":
    main()
