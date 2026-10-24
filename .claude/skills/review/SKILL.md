---
name: review
description: "Spawn the global reviewer on a branch diff with filmstrip's lenses: cross-backend contracts, preview parity, KDoc rules, ABI. At most 15 severity-tagged lines plus a verdict. Use before /merge."
argument-hint: "[branch | path... | blank for the working changes against main]"
---

Delegate, do not read the diff here. A reviewer counts toward the three running agents.

## 1. Precheck, one Bash
```sh
bash .claude/skills/review/precheck.sh <base> <branch or worktree>
```
It prints `path:line: reason` for mechanical hits in changed Kotlin: lines past 120 columns, leftover `// <slug>-u<n>` or `-m<n>` markers, files past 500 lines, commas in Native test names. Hits ride with the builder brief of the next fix, or go to a scribe when there is none. The reviewer sees judgement items only.

## 2. Diff by path
Write `git diff <base>...<branch>` to `scratchpad/reviews/<branch with / as ->.diff`. Working changes: `git diff` and `git diff --cached` into the same file. Hand the reviewer the path, never a command to run.

Units of one wave that are small go to one reviewer, one diff file per branch.

## 3. Spawn
`Agent`, `subagent_type: "reviewer"`, no `model:`, prompt:
```
Diff: read scratchpad/reviews/<file>.diff | files: ….
Plan and unit, if one exists: scratchpad/plans/<slug>.md section "U<n>" (files owned, tests, verification).
Mechanical hits are already routed. Skip wrap, markers, file size and test name commas.
List everything in one pass. A finding held for a later round is a miss.
Lenses in priority: <any the owner named>, then your standard set, then these:
- Cross-backend contracts (CLAUDE.md): a quantity all four backends read is computed once in the lowest module
  that sees it and consumed, not re-derived. A backend diverges only for its own limit, clamped visibly with a
  comment naming whose limit, and tested. Tests assert against the shared function, not a copied literal, and
  read the middle of a range, not only its ends. A re-derived number is 🔴.
- Preview parity: an export change without the matching preview change on the same backend, or the reverse.
- Public API: every public declaration has a KDoc per CLAUDE.md (never one line, wrapped at 120, no mention of
  ADRs or docs/research, explains the code not the decision). A moved public signature in core, effects,
  player, compose, compose-ui or test needs its api/ dump updated. @InternalFilmstripApi is excluded from the
  dumps, so a change there passes checkKotlinAbi and needs your eyes.
- No @Serializable in the edit model. A Kotlin Map handed to an AVFoundation or VideoToolbox API is 🔴 unless
  built as a real dictionary.
- A caller-visible behaviour change without a docs/capabilities.md edit is 🟡.
- Standing checklist, every pass: concurrency and cache keys (races, waiters, what the key leaves out), a
  timeout on every wait, every size or allocation bound on hostile input, each commit compiles on its own.
A 🟠 names the failing input or test that shows it. Without one, tag it 🟡.
Return at most 15 lines `path:line: severity: problem. fix.` and a `verdict:` line.
```

## 4. Then
- 🔴: fix before merge. A fix goes to a fresh `builder` with the reviewer's lines and the precheck hits pasted and the branch named, never to the builder that wrote it.
- 🟠 with a failing input or test: same as 🔴. Without one it joins 🟡.
- 🟡 and leftover 🟠: note them in the plan's section of `scratchpad/upnext.md`. They collect into one polish unit per module at the end of the chain, a scribe when mechanical. Never a unit per finding.
- 🔵: leave for spotless and lint.
- A `merge` verdict spawns no reviewer follow-up. Precheck hits still go to a scribe before `/merge`.

Two fix-first rounds per unit at most. A third → stop, an `analyst` writes the design, then one builder and one review.

Re-review a fix only when a line of the last review is left open. Pixel paths, hostile-input parsers and public API always get one. Every line addressed on any other unit → `/merge`.

Reply with the reviewer's lines verbatim and what was done about each, then the verdict.
