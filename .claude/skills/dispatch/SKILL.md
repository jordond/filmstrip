---
name: dispatch
description: "Spawn builder agents in worktrees for a plan's units, with the base sha and the unit's verification, and note them on scratchpad/upnext.md."
argument-hint: "<plan slug> [U<n> ...], or the plan slug alone to list its units"
---

Routing, model and the builder's report shape come from the global agents and `/spawn`. This skill adds filmstrip's overrides: **at most three sub-agents running at once, reviewers included**, one fresh agent per unit, and a base sha every worktree verifies.

## Preflight, one Bash
```sh
git status --short | head -5; git worktree list | grep -v '/agent-'; git log -1 --format=%h <base branch>
```
The base is the plan's integration branch head when it has one (a named worktree under `.claude/worktrees/`), else `main`. Count the agents you have running. Three running → queue the rest and say so. No two units running at once may own the same file, and a unit whose plan says it lands after another waits until that one is merged.

## Units
`$ARGUMENTS`: a plan slug and unit ids. Slug alone → list the plan's units with state from the board and stop.

## Investigation first
A unit that is an investigation ("find why X fails") goes to an `analyst` first. Its design goes into the builder brief. A builder never gets a brief that has no design.

## Spawn, independent units in one message
`Agent`, `subagent_type: "builder"` (`"scribe"` only for a fully spelled out edit of at most three files), `isolation: "worktree"`, `run_in_background: true`, `description: "<slug> U<n>"`. No `model:`, the agent file owns it. Prompt:
```
Plan: scratchpad/plans/<slug>.md, section "U<n>" and section "Verification rules, every unit". Read those,
not the whole plan, then only the files the unit lists.
Base: <sha>. First run `git log -1 --format=%H`. If it is not <sha>, `git reset --hard <sha>` before anything else.
Branch: fix/<slug>-u<n>.
Runs on: <Mac | cerebro via `scratchpad/remote/gradle.sh <this worktree> <slug>-u<n> <task>`, the script lives in
the main checkout at /Users/jordon/dev/filmstrip>.
Gradle: one test task per call, foreground, never piped to head. ffmpeg jvmTest and any Apple suite never in one
call or at once. Run at the head only. Report fresh counts from the result XML against the baseline below.
Baseline: <the board's last verified counts for the suites this unit runs, or "none recorded, your head run sets it">.
Verification scope: <the rules below, filled in for this unit>.
Running at the same time (do not touch their files): <slug> U<m>: <paths>; …
If measured behaviour is nondeterministic or a platform limit, stop and report it, do not pin it.
Budget: finish under 250k context, about 90 minutes wall and about 20 `gradle.sh` calls. Past any of them, stop,
commit what is done and report what is left.
Return the 40-line report from your agent definition.
```
Point at the plan section, never paste it. A pixel path unit gets `connectedAndroidDeviceTest` at its head in its verification, if the plan does not already say so. Never a run at the base sha, the baseline is the board's last verified counts. No counts on the board → the brief says `none recorded` and the first head run sets the baseline.

## Verification scope
Fill in the brief's scope from the files the unit owns.
- Android device: API 30 and 36. A sweep of every level only when the unit edits a landing table (`heicLanding`, `avifLanding`, `DecoderLimit`).
- Apple: macOS with `--tests` on the touched classes. iOS simulator only when the unit edits a simulator decode model, or in the final lane.
- Player suites only when the unit touches the player.
- ffmpeg on one host. A core-only unit runs the picture classes only.

## Watch
While builders run, keep `/spawn`'s watchdog going in the background (`python3 ~/.claude/skills/spawn/watchdog.py --repo /Users/jordon/dev/filmstrip --session <session id>`). Otherwise no polling and no reading transcripts, the completion notification wakes you.

## Register
Note each spawned unit in the plan's section of `scratchpad/upnext.md`: `U<n> dispatched <date>, fix/<slug>-u<n>, <worktree>`.

## On report
A follow-up, a review fix or the next unit goes to a **fresh** agent with a brief naming the branch and what is left, never a `SendMessage` to one that already finished. A "pre-existing failure" in a report is a claim until the board's last verified counts show it. Then `/review <branch>` per its re-review rule, then `/merge <branch>`.

## Reply
One line per agent: unit, where it runs, worktree. Then `waiting`.
