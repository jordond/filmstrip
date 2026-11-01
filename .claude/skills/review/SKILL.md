---
name: review
description: "Spawn the global reviewer on a branch diff with filmstrip's lenses: cross-backend contracts, preview parity, KDoc rules, ABI. At most 15 severity-tagged lines plus a verdict. Use before /merge."
argument-hint: "[branch | path... | blank for the working changes against main]"
---

Delegate, do not read the diff here. A reviewer counts toward the three running agents. One review per plan, on the
whole branch, after every unit is done.

## 0. Skip
No reviewer when the branch changes no production code: only tests, CI, docs, build files or skills. Run the precheck,
read the builder's open-lens lines, and merge. A scribe fixes precheck hits.

## 1. Precheck, one Bash
```sh
bash .claude/skills/review/precheck.sh <base> <branch or worktree>
```
It prints `path:line: reason` for mechanical hits in changed Kotlin: lines past 120 columns, leftover `// <slug>-u<n>` or `-m<n>` markers, files past 500 lines, commas in Native test names. Hits ride with the builder brief of the next fix, or go to a scribe when there is none. The reviewer sees judgement items only.

## 2. Diff by path
Write `git diff <base>...<branch>` to `scratchpad/reviews/<branch with / as ->.diff`. Working changes: `git diff` and `git diff --cached` into the same file. Hand the reviewer the path, never a command to run.

## 3. Spawn
`Agent`, `subagent_type: "reviewer"`, no `model:`, prompt:
```
Diff: read scratchpad/reviews/<file>.diff | files: ….
Plan: scratchpad/plans/<slug>.md, its goal and units (files owned, tests, verification).
Mechanical hits are already routed. Skip wrap, markers, file size and test name commas.
List everything in one pass. A finding held for a later round is a miss.
Lenses in priority: <any the owner named>, then your standard set, then .claude/skills/review/lenses.md.
The builder already went through its diff against those lenses and lists what it left open. Read that list.
Judge whether the diff does what the plan says without breaking anything. A problem outside the plan's goal, or one
that was there before the diff, is one trailing line for the board, not a finding.
A 🟠 names the failing input or test that shows it. Without one, tag it 🟡. Only 🔴 and 🟠 make the verdict fix-first.
Return at most 15 lines `path:line: severity: problem. fix.` and a `verdict:` line.
```

## 4. Then
- 🔴, and 🟠 with a failing input: one fresh `builder` with every such line and the precheck hits pasted, the branch
  named. No re-review unless a 🔴 was in it, then a narrow one on the fix commits only.
- 🟡, 🔵, untested 🟠 and the trailing board line: note them on `scratchpad/upnext.md` under the plan. They never
  loop and never become a unit of this plan.
- A second fix-first verdict on the same plan → stop and report the lines.

Reply with the reviewer's lines verbatim and what was done about each, then the verdict.
