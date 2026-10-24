---
name: merge
description: "Land a unit branch onto its plan's integration branch by cherry-pick, or squash an integration branch onto main on the owner's go. Hand-resolved conflicts, companion checklist, board update. Use when a builder reports done."
argument-hint: "<unit branch> | --main <integration branch>"
---

Merges have lost work before. Keep the order.

## 1. Identify
`git worktree list` and `git branch -vv`. A unit branch (`fix/<slug>-u<n>`) lands on its plan's integration branch `fix/<slug>`, checked out in `.claude/worktrees/<slug>`. No integration branch yet → create it from the plan's base sha with `git worktree add .claude/worktrees/<slug> -b fix/<slug> <sha>`. Use the builder's report from the notification, never a re-read transcript.

## 2. Unit → integration branch, by cherry-pick
Worktree bases here have been stale, so a merge drags in the wrong history. In the integration worktree:
```sh
git log --oneline <base sha>..<unit branch>    # the unit's own commits, nothing older
git cherry-pick <first>^..<last>
```
Conflicts, by hand:
- An append-only surface (a new `when` arm, a new test case, a doc table row, a `// <slug>` marked block): keep both sides in order, drop the markers.
- Anything else: read both hunks and write the merged content with `Edit`. Never `git checkout --ours|--theirs <file>`, it drops the cleanly merged hunks too.
- `grep -rn '^<<<<<<<\|^=======\|^>>>>>>>' <files>` comes back empty, `git add`, `git cherry-pick --continue`.

A conflict you cannot settle from the two sides and the plan → stop, name the file and both sides.

## 3. Companion checklist
Read `checklist.md` here and run the rows the diff hits. A fix goes to a fresh `builder` or `scribe` with the lines pasted, landed as its own commit on the integration branch.

## 4. Verify once per batch
Cherry-pick every unit of the wave first, then one `/ci` against the integration worktree. Red → one fresh builder with the failing lines, one re-run. Never a retry loop.

## 5. Integration branch → `main`, only on the owner's go
The owner decides when an integration branch lands. With the go:
```sh
git switch main && git merge --squash fix/<slug> && git commit
```
A semantic commit message, brief, in the owner's voice, no attribution lines. Never push without the owner saying so, and never open a PR or issue.

## 6. Board
`scratchpad/upnext.md`: the unit's line → `landed on fix/<slug> <date>, <sha>`, report follow-ups into the branch section's loose ends, fresh counts into its verified line. A follow-up that would undo or redo what the unit just landed means the unit was a stop-gap: the proper fix goes into the chain before the branch goes to `main`, not onto the board. After a squash onto `main`, retitle the section `squash merged to main as <sha> on <date>` and update the header sha.

## 7. Clean up
Only after the report is folded into the board: `git worktree remove <path>` (add `--force` only when nothing uncommitted is left in it) and `git branch -D <unit branch>`.

## Reply, at most 8 lines
Commits landed and where, conflicts and how each was resolved, checklist findings, CI state, board updated, next.
