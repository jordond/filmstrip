---
name: ci
description: "Run filmstrip's full CI lane (spotless, ABI, layer guards, lint, every test target, sample build) one Gradle call per step in the background and read only the digest. Use once per plan after its review, never inline."
argument-hint: "[--remote] [--device] [--rerun] [--only step,step] [checkout] | --digest"
---

Never run the whole lane inline, its output is long and a builder already ran its own narrow slice.

- `$ARGUMENTS` is `--digest` → re-read the last digest: `for f in "${TMPDIR:-/tmp}"/filmstrip-ci/*/*.log; do echo "$f"; grep -a '^RESULT' "$f"; done`, report. Each path names the checkout its lane ran.
- Otherwise: `Bash`, `run_in_background: true`, command `bash .claude/skills/ci/ci.sh $ARGUMENTS`. Do something else or end the turn, the completion notification wakes you. Read only the digest and the trailing `exit=N` (the number of `FAIL` and `unknown` steps), never a whole log.

Steps, each its own Gradle call, in CI's order: `spotless`, `abi`, `guards`, `lint`, `android-host`, `jvm`, `wasm`, `js`, `macos`, `ios-boot`, `ios`, `sample`, and `device` with `--device`. `jvm` and `macos` run one after the other, so ffmpeg and Apple never share VideoToolbox.
- `--remote` sends every step except `abi`, `macos` and the `ios` pair to cerebro, for when the owner wants the Mac free.
- `--device` needs an emulator booted first: the local AVD per memory `running-android-device-suite`, or `scratchpad/remote/emulator.sh start` with `--remote`. Required for green on any preview, readback, thumbnail or export pixel path.
- A checkout argument runs the lane against an integration worktree instead of `main`.
- A test task Gradle skips as `UP-TO-DATE` or restores `FROM-CACHE` passed at these exact inputs, so its existing result XML counts and the digest shows `cached=<n>`. Gradle never caches a failed test task. Local steps only: `--remote` still reports a cached step as `unknown`.
- `unknown` with `no result XML written or cached` means the step passed without any test result. It is not green and counts toward `exit`.
- `--rerun` passes Gradle `--rerun` to each test step. Every test step is one task, since `--rerun` in a call with several tasks reruns only the last. Only for a suspected flake or an `unknown` step, never to get counts.

Red:
- `unknown` → rerun that step with `--only <step> --rerun`.
- A single `failed=` count in an encoder test → re-run that step alone with `--only <step>` before believing it.
- `failure message="Unknown"` → a cancelled client, not a crash. Check the daemon log per memory `cancelled-gradle-looks-like-crash`.
- A real failure: one fresh `builder` with the failing lines pasted, not the log path, then one re-run. The orchestrating session never edits the code itself.

Reply: `ci: exit=<n> @ <sha>` plus the failing lines, nothing else.
