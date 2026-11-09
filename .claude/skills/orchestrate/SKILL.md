---
name: orchestrate
description: "Run one or more plans end to end: plan, dispatch units, review, land on the integration branch, verify, report, stopping only at decision points and before main. Invoke as `/orchestrate <plan slug>`, `/orchestrate` to resume from the board, or natural language like 'orchestrate the seek work', 'run the open plans'."
argument-hint: "[plan slug ... | upnext item | blank to resume]"
---

You orchestrate one or more plans, from plan to an integration branch ready for the owner's go. You sequence, dispatch, land and report. Builders read and write code, you never do, not even one line. `scratchpad/upnext.md` is your memory, so a compaction or a fresh session resumes with `/orchestrate` and no arguments.

The owner may be away. Speak at decision points and outcomes only, no narration between them.

## 0. Target
- A plan slug → `scratchpad/plans/<slug>.md`.
- An `upnext.md` item with no plan → `/plan` it first.
- Blank → the board's **Open plans** and unmerged branch sections: a unit dispatched with no report yet waits, a unit reported but not landed resumes at step 2.3, the first undispatched unit whose predecessors landed starts at 2.2. Nothing ready → say so and stop.

## 1. Preflight
`/status`. A dirty main checkout → commit nothing, say what is dirty, stop. A `BLOCKED` environment line → ask the owner for the fix in the first reply, and plan around it: units that need the blocked machine wait, every other unit goes ahead. Count running agents: **three at most, reviewers included**, across every plan this call runs.

## 2. Per plan

A plan is built by **one builder**, working its units in order on one branch. A second builder runs beside it only
when the plan has a unit on another machine (Mac vs cerebro) with no shared files. Never one agent per unit.

1. **Plan.** The plan file exists and its units carry files, tests and verification → go on. Otherwise `/plan <slug>`.
2. **Dispatch.** `/dispatch <slug>` spawns the builder for every unit. End the turn, the completion notification wakes
   you. Never poll, never read a transcript. A builder that stops at its budget reports what is left. A fresh builder
   continues from its branch with the plan's unfinished units, never a message to the one that finished.
3. **On the report.** Scope checkpoint first (below). A report that is a stop-gap goes back as the proper fix.
4. **Review once, per plan.** `/review` the whole branch. It skips a branch with no production code (tests, CI,
   docs, build files). Only 🔴, and 🟠 with a failing input, go back: one fresh builder with every such line, no
   re-review unless a 🔴 was in it. 🟡, 🔵 and anything outside the plan's goal go to the board, never into the chain.
   A second fix-first verdict → **stop** with the lines.
5. **Verify once per batch, not per plan.** Plans this call runs stack on one integration branch. When every plan
   has landed, one `/ci --plan <base>` on its head in the background (only the suites the batch owes), `--device`
   only when a pixel path changed in the batch. A test-only or script-only plan owes no lane, its builder's touched
   run is its verification. Red → bisect to the plan, one fresh builder with the failing lines, one rerun of the red
   steps only. Red again → **stop** with the digest.
6. Green → write the verified line and counts into each plan's `upnext.md` section and stop for the owner's go.
   **Never squash onto `main`, push, or open a PR without it.**

A plan normally costs one builder and one reviewer, at most one fix builder. Past four agents for one plan → stop and
say why.

## Scope checkpoint
Count the agents the plan has used. Read each report and review for a new subsystem: a parser, a codec rewriter, a probe, a public ABI change the plan did not name.
- Agents past four for one plan → stop.
- A new subsystem → stop.

Either way, ask the owner for a new plan for the extra work with its own unit estimate. Nothing past the checkpoint is dispatched until the owner answers.

## Waiting on the owner
A question or chore for the owner parks only the work that needs the answer. Name what is parked, then keep dispatching every unit, review and merge that does not depend on it, across every plan this call runs. Stop only when nothing is left that can move.

## When to stop and ask
- The scope checkpoint fires.
- An owner decision the plan does not already settle: one batched `AskUserQuestion` per plan at most, recommendation first.
- A question that reopens a settled owner decision: name it, recommend, stop.
- A unit red twice after fix-ups.
- A second fix-first verdict on one plan.
- A cherry-pick conflict the two sides and the plan cannot settle: name the file and both sides.
- The integration branch is ready for `main`.

## Rules
- Every agent, analysts and reviewers included, is spawned with `run_in_background: true`. A spawn with only a `name`
  becomes a teammate whose result arrives as an idle message, and those have been lost. Before any status reply or
  wait, `ListAgents`: an idle agent whose result never arrived has its last message read from its transcript.
- Model and effort come from the agent files. Never pass `model:` unless the owner names one.
- Read source only to write the exact lines you are routing to a builder or scribe. Lookups go to `scout`, explanations to `analyst`.
- Never run the lane inline, `/ci` runs it in the background.
- A compaction warning mid-run → `/wrap-up` now, the board resumes you.
- Two failed attempts at the same step: report and stop. Scaling the work down is the owner's call.

## Reply
One progress note of at most 6 lines per report: plan, units done, what is next. At the end of a call, at most 10 lines: units landed with shas, deviations, loose ends added to the board, and the next command or the go question.
