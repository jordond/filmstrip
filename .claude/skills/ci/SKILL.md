---
name: ci
description: "Run filmstrip's full CI lane (spotless, ABI, layer guards, lint, every test target, sample build) one Gradle call per step in the background and read only the digest. Use once per batch after review, never inline."
argument-hint: "[--plan [base]] [--remote] [--hosts a,b] [--device] [--fast] [--full] [--rerun] [--dry] [--only step,step] [checkout] | --digest"
---

Never run the whole lane inline, its output is long and each unit already ran its own narrow slice.

- `$ARGUMENTS` is `--digest` → re-read the last digest: `for f in "${TMPDIR:-/tmp}"/filmstrip-ci/*/*.log; do echo "$f"; grep -a '^RESULT' "$f"; done`, report. Each path names the checkout its lane ran.
- Otherwise: `Bash`, `run_in_background: true`, command `bash .claude/skills/ci/ci.sh $ARGUMENTS`. Do something else or end the turn, the completion notification wakes you. Read only the digest and the trailing `exit=N` (the number of `FAIL` and `unknown` steps), never a whole log.

Steps, each its own Gradle call, in CI's order: `spotless`, `build-logic`, `abi`, `guards`, `lint`, `android-host`, `jvm`, `wasm`, `js`, `macos`, `ios-boot`, `ios`, `sample`, and `device` with `--device`. The digest prints in this order, each line with its start time and chain.
- Every test step passes `-Pmedia -Phardware -PpureEverywhere`, so the lane runs media, hardware and pure tests on every target. `--fast` drops them and runs what a push runs.
- The `ios` step runs only what only iOS runs: the `iosTest`, `iosSimulatorArm64Test` and `appleHostTest` classes plus the canary classes in `TestShards.kt`. Shared `commonTest` and `appleTest` classes run once, in `macos`. It names only the modules `printIosTestModules` prints, so a module with nothing to run on the simulator is never linked. `--full` passes `-PiosShared` to `ios` and runs every module there, so the simulator runs the shared classes too. Use it before a release or when a change touches an Apple API whose iOS behaviour can differ from macOS.
- Without `--remote` every step runs on the Mac one at a time, so ffmpeg and Apple never share VideoToolbox.
- `--remote` runs two chains at once. The mac chain (`abi`, `wasm`, `js`, `macos`, `ios-boot`, `ios`) stays on the Mac, since cerebro has no working ChromeHeadless. The remote chain sends the rest, `device` included, to `FILMSTRIP_REMOTE` or cerebro. Wall time is the longer chain.
- `--hosts a,b` implies `--remote` and splits the remote chain across two hosts, in either order. The heavy host, `cerebro` when listed, runs `jvm` and `device`. The light host runs the short steps. `jvm` splits the ffmpeg suite 3:1 by `-PtestShard`: shards 1-3 as `jvm` on the heavy host in three test JVMs (`-PtestForks=3`), shard 4 as `jvm-shard` on the light one, which also runs every other module's `jvmTest` as `jvm-rest`. `--only jvm` picks all three. A host is `cerebro`, `hoogflix` or `user@address`.
- `--dry` prints each step's chain, host, properties and tasks and runs nothing. Use it to check a flag combination.
- `--plan [base]` runs only the suites a plan owes: `git diff --name-only base...HEAD` (base defaults to `main`, a range `a..b` is diffed as given) mapped to modules, their source sets' targets and every module that depends on them, each test step scoped to those modules' tasks. It prints what each changed module owes first. A change under `build-logic`, `gradle` or `kotlin-js-store`, or to a root build file, owes every step. The device step is only named unless `--device` is passed. The graph is `printModuleGraph`, one Gradle configure per change to the build files. A scoped step holds several tasks, so `--rerun` reruns only its last.
- `--device` needs an emulator booted first: the local AVD per memory `running-android-device-suite`, or `scratchpad/remote/emulator.sh start` with `--remote`. Required for green on any preview, readback, thumbnail or export pixel path.
- A checkout argument runs the lane against an integration worktree instead of `main`.
- A test task Gradle skips as `UP-TO-DATE` or restores `FROM-CACHE` passed at these exact inputs, so its existing result XML counts and the digest shows `cached=<n>`. Gradle never caches a failed test task. Local steps only: `--remote` still reports a cached step as `unknown`.
- `unknown` with `no result XML written or cached` means the step passed without any test result. It is not green and counts toward `exit`.
- A remote step runs detached on its host and `gradle.sh` reconnects after a dropped ssh, so a drop no longer kills the build. `lost` means the host stayed unreachable for the whole reconnect window, about 10 min. It counts toward `exit`.
- `--rerun` passes Gradle `--rerun` to each test step. Every test step is one task, since `--rerun` in a call with several tasks reruns only the last. Only for a suspected flake or an `unknown` step, never to get counts.

Red:
- `unknown` → rerun that step with `--only <step> --rerun`.
- A single `failed=` count in an encoder test → re-run that step alone with `--only <step>` before believing it.
- `failure message="Unknown"` → a cancelled client, not a crash. Check the daemon log per memory `cancelled-gradle-looks-like-crash`.
- A real failure: fix it in this session. Then one re-run of the red steps.

Reply: `ci: exit=<n> @ <sha>` plus the failing lines, nothing else.
