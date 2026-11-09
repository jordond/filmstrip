---
name: dispatch
description: "Spawn one builder in a worktree for a plan's units, with the base sha and the verification, and note it on scratchpad/upnext.md."
argument-hint: "<plan slug> [U<n> ...]"
---

Routing, model and the builder's report shape come from the global agents and `/spawn`. This skill adds filmstrip's overrides: **one builder per plan**, working its units in order on one branch, **at most three sub-agents running at once**, and a base sha every worktree verifies.

## Preflight, one Bash
```sh
git status --short | head -5; git worktree list | grep -v '/agent-'; git log -1 --format=%h <base branch>
```
The base is the plan branch head when it has one, else `main`. Count the agents you have running. Three running → queue and say so.

A second builder for the same plan only when some units must run on another machine (Mac vs cerebro) and own no file the first builder touches. It gets its own branch, `fix/<slug>-<machine>`.

## Units
`$ARGUMENTS`: a plan slug, optionally unit ids. Slug alone → every unit not yet done, in the plan's order.

## Investigation first
A unit that is an investigation ("find why X fails") goes to an `analyst` first. Its design goes into the builder brief. A builder never gets a brief that has no design.

## Spawn
`Agent`, `subagent_type: "builder"` (`"scribe"` only for a fully spelled out edit of at most three files), `isolation: "worktree"`, `run_in_background: true`, `description: "<slug>"`. No `model:`, the agent file owns it. Prompt:
```
Plan: scratchpad/plans/<slug>.md, units <U<n>, ...> in that order, and section "Verification rules, every unit".
Read those sections, then only the files each unit lists. One commit per unit, each green on its own.
Base: <sha>. First run `git log -1 --format=%H`. If it is not <sha>, `git reset --hard <sha>` before anything else.
Branch: fix/<slug>.
Runs on: <Mac | cerebro via `scratchpad/remote/gradle.sh <this worktree> <slug> <task>`, the script lives in
the main checkout at /Users/jordon/dev/filmstrip>. When cerebro's Gradle lock is held, run on hoogflix
instead with `FILMSTRIP_REMOTE=user@10.0.0.15`, never queue behind it.
Gradle: one test task per call, foreground, never piped to head. ffmpeg jvmTest and any Apple suite never in one
call or at once. Iterate on the touched classes with `--tests`. Run the touched classes on each target in your scope
once at the final head, never a whole suite, the batch lane does that. `spotlessApply` once before each commit, never
`spotlessCheck` in a loop. Report fresh counts from the result XML against the baseline below.
Baseline: <the board's last verified counts for the suites this unit runs, or "none recorded, your head run sets it">.
Verification scope: <the rules below, filled in for this unit>.
Running at the same time (do not touch their files): <other agents and their paths, or "nothing">.
If measured behaviour is nondeterministic or a platform limit, stop and report it, do not pin it.
Stay on the plan's goal. A bug you find outside it goes in the report as a follow-up, not into the branch.
Prove a test can fail with a temporary mutation only for a new fence, seam or limit, not for every test.
Budget: finish under 250k context. Past it, commit the units that are done and report which are left.
Self-check before you report: run `bash /Users/jordon/dev/filmstrip/.claude/skills/review/precheck.sh <base> .`
and fix every hit. Then go through your diff against /Users/jordon/dev/filmstrip/.claude/skills/review/lenses.md,
fix what it finds, and end the report with one line per lens you left open and why. A reviewer gets the same list.
Return the 40-line report from your agent definition.
```
Point at the plan sections, never paste them. A pixel path unit gets `connectedAndroidDeviceTest` at its head in its verification, if the plan does not already say so. Never a run at the base sha, the baseline is the board's last verified counts. No counts on the board → the brief says `none recorded` and the first head run sets the baseline.

## Verification scope
Fill in the brief's scope from the files the unit owns.
- Each unit compiles and runs its new or changed test classes on one target. The touched classes on every target
  the plan names run once, at the plan's head, not per unit. Never a whole suite, the batch lane does that.
- Android device: API 36 only, touched classes only. API 30 joins only when the unit edits an encoder or decoder
  limit, otherwise the batch lane covers it. A sweep of every level only when the unit edits a decoder conversion
  table (`decoderConversionOf`, `readableMatrices`, `DecoderLimit`).
- Apple: macOS with `--tests` on the touched classes. iOS simulator only when the unit edits a simulator decode model, or in the final lane.
- Player suites only when the unit touches the player.
- A `*MediaTest` class needs `-Pmedia`, a `*HardwareTest` class `-Phardware`, or Gradle finds no tests.
- ffmpeg on one host. A core-only unit runs the picture classes only.

## Watch
While builders run, keep `/spawn`'s watchdog going in the background (`python3 ~/.claude/skills/spawn/watchdog.py --repo /Users/jordon/dev/filmstrip --session <session id>`). Otherwise no polling and no reading transcripts, the completion notification wakes you.

## Register
Note the builder in the plan's section of `scratchpad/upnext.md`: `dispatched <date>, units <list>, fix/<slug>, <worktree>`.

## On report
Units left over (budget hit) go to a **fresh** builder on the same branch, never a `SendMessage` to one that finished. A "pre-existing failure" in a report is a claim until the board's last verified counts show it. Every unit done → `/review <branch>` once for the plan.

## Reply
One line per agent: units, where it runs, worktree. Then `waiting`.
