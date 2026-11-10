---
name: merge
description: "Land a fix or second-builder branch onto its plan branch by cherry-pick, or squash a plan branch onto main on the owner's go. Hand-resolved conflicts, companion checklist, board update. Use when a builder reports done."
argument-hint: "<branch> | --main <plan branch>"
---

Merges have lost work before. Keep the order.

## 1. Identify
`git worktree list` and `git branch -vv`. The plan's builder commits on `fix/<slug>`, which is the plan branch. Only a
second builder's branch (`fix/<slug>-<machine>`) or a fix builder's branch lands onto it. Check it out in
`.claude/worktrees/<slug>` (`git worktree add .claude/worktrees/<slug> fix/<slug>`) when it is not already. Use the
builder's report from the notification, never a re-read transcript.

## 2. Branch → plan branch, by cherry-pick
Worktree bases here have been stale, so a merge drags in the wrong history. In the plan worktree:
```sh
git log --oneline <base sha>..<branch>    # its own commits, nothing older
git cherry-pick <first>^..<last>
```
Conflicts, by hand:
- An append-only surface (a new `when` arm, a new test case, a doc table row, a `// <slug>` marked block): keep both sides in order, drop the markers.
- Anything else: read both hunks and write the merged content with `Edit`. Never `git checkout --ours|--theirs <file>`, it drops the cleanly merged hunks too.
- `grep -rn '^<<<<<<<\|^=======\|^>>>>>>>' <files>` comes back empty, `git add`, `git cherry-pick --continue`.

A conflict you cannot settle from the two sides and the plan → stop, name the file and both sides.

## 3. Companion checklist
Read `checklist.md` here and run the rows the diff hits. Fix hits in this session, only a big job goes to a fresh `builder` with the lines pasted. Either lands as its own commit on the plan branch.

## 4. Verify once per plan
`/orchestrate` runs the lane once, after the review, against the plan worktree. Red → fix it here, or one fresh builder if it is a big job, with the failing lines, then one re-run. Never a retry loop.

## 5. Integration branch → `main`, only on the owner's go
The owner decides when an plan branch lands. With the go:
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
