---
name: orchestrate
description: "Run one or more plans end to end in this session: plan, build each unit, self-review, verify, report, stopping only at decision points and before main. Invoke as `/orchestrate <plan slug>`, `/orchestrate` to resume from the board, or natural language like 'orchestrate the seek work', 'run the open plans'."
argument-hint: "[plan slug ... | upnext item | blank to resume]"
---

You run one or more plans, from plan to an integration branch ready for the owner's go, and you do all of it
yourself: research, code, tests, review, fixes. No sub-agents. `scratchpad/upnext.md` is your memory, so a fresh
session resumes from a `/wrap-up` continuation prompt or `/orchestrate` with no arguments.

The owner may be away. Speak at decision points and outcomes only, no narration between them.

## 0. Target
- A plan slug → `scratchpad/plans/<slug>.md`.
- An `upnext.md` item with no plan → `/plan` it first.
- Blank → the board's **Open plans** and unmerged branch sections: resume at the first unit not marked landed, on its
  plan branch. Nothing ready → say so and stop.

## 1. Preflight
`/status`. A dirty main checkout → commit nothing, say what is dirty, stop. A `BLOCKED` environment line → ask the
owner for the fix in the first reply and plan around it: units that need the blocked machine wait, every other unit
goes ahead.

## 2. Per plan
1. **Plan.** The plan file exists and its units carry files, tests and verification → go on. Otherwise `/plan <slug>`.
2. **Worktree.** Work in `.claude/worktrees/<slug>` on `fix/<slug>`, cut from `main` (or the previous plan's branch
   when plans stack). `git worktree add -b fix/<slug> .claude/worktrees/<slug> <base>` when it does not exist.
3. **Build, unit by unit, in order.** For each unit: read its section and only the files it lists, write the tests
   failing first, implement, `spotlessApply`, run the touched classes on one target (per **Verification scope**
   below), commit. Mark the unit `landed on fix/<slug> <date>, <sha>` on the board right after the commit, so a
   handoff loses nothing.
4. **Self-review once, per plan.** `/review` the whole branch when every unit is done. Fix 🔴 and 🟠 here. 🟡 and 🔵
   inside the plan's goal are fixed on the branch too, per memory `followups-go-in-the-chain`.
5. **Verify once per batch, not per plan.** Plans this call runs stack on one integration branch. When every plan has
   landed, one `/ci --plan <base>` on its head in the background, `--device` only when a pixel path changed. A
   test-only or script-only plan owes no lane. Red → bisect to the plan, fix it, one rerun of the red steps only. Red
   again → **stop** with the digest.
6. Green → write the verified line and counts into each plan's `upnext.md` section and stop for the owner's go.
   **Never squash onto `main`, push, or open a PR without it.**

## Verification scope
- A unit runs its new or changed test classes on one target with `--tests`. The touched classes on every target the
  plan names run once, at the plan's head. Never a whole suite, the lane does that.
- One test task per Gradle call, foreground, never piped to head. ffmpeg jvmTest and any Apple suite never in one call
  or at once.
- Android device: API 36 only, touched classes only. API 30 only when the unit edits an encoder or decoder limit. A
  sweep of every level only when the unit edits a decoder conversion table (`decoderConversionOf`,
  `readableMatrices`, `DecoderLimit`).
- Apple: macOS with `--tests`. iOS simulator only when the unit edits a simulator decode model, or in the lane.
- A `*MediaTest` class needs `-Pmedia`, a `*HardwareTest` class `-Phardware`, or Gradle finds no tests.
- Non-Apple suites go to cerebro with `scratchpad/remote/gradle.sh <worktree> <slug> <task>`, or hoogflix with
  `FILMSTRIP_REMOTE=user@10.0.0.15` when cerebro's lock is held. The Mac keeps Apple suites and ABI updates.
- Prove a test can fail with a temporary mutation only for a new fence, seam or limit.
- Behaviour that measures nondeterministic or hits a platform limit: name it, do not pin it.

## Scope checkpoint
A new subsystem the plan did not name (a parser, a codec rewriter, a probe, a public ABI change) → stop and ask the
owner for a new plan for the extra work, with its own unit estimate.

## Context handoff
Watch the context. At 150k to 200k, finish the unit in hand (commit it or stash nothing half done), then `/wrap-up`,
which writes the continuation prompt and pings the owner. Stop there. Never start a new unit past 150k.

## Waiting on the owner
A question parks only the work that needs the answer. Name what is parked, then keep going on every unit that does not
depend on it. Stop only when nothing is left that can move.

## When to stop and ask
- The scope checkpoint fires.
- An owner decision the plan does not already settle: one batched `AskUserQuestion` per plan at most, recommendation
  first.
- A question that reopens a settled owner decision: name it, recommend, stop.
- A unit red twice after fixes.
- The integration branch is ready for `main`.
- The context handoff.

## Rules
- No sub-agents, no `/spawn`. Lookups, research, reviews and fixes all happen here.
- Never run the lane inline, `/ci` runs it in the background. While it runs, keep working on what does not depend on it.
- Two failed attempts at the same step: report and stop. Scaling the work down is the owner's call.

## Reply
At the end of a call, at most 10 lines: units landed with shas, deviations, loose ends, and the next command or the
go question.
