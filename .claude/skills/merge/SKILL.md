---
name: merge
description: "Squash a plan branch onto main on the owner's go, or rebase a stacked plan branch onto its new base. Companion checklist, hand-resolved conflicts, board update."
argument-hint: "--main <plan branch> | --rebase <plan branch> <new base>"
---

Merges have lost work before. Keep the order.

## 1. Identify
`git worktree list` and `git branch -vv`. Plan work lives on `fix/<slug>` in `.claude/worktrees/<slug>`.

## 2. Companion checklist
Read `checklist.md` here and run the rows the diff hits. Fix hits in the plan worktree as their own commit.

## 3. Rebase a stacked branch
When the branch below it changed: `git rebase --onto <new base> <old base> fix/<slug>` in its worktree. Conflicts, by
hand:
- An append-only surface (a new `when` arm, a new test case, a doc table row): keep both sides in order.
- Anything else: read both hunks and write the merged content with `Edit`. Never `git checkout --ours|--theirs <file>`,
  it drops the cleanly merged hunks too.
- `grep -rn '^<<<<<<<\|^=======\|^>>>>>>>' <files>` comes back empty, `git add`, `git rebase --continue`.

A conflict you cannot settle from the two sides and the plan → stop, name the file and both sides.

## 4. Plan branch → `main`, only on the owner's go
Per memory `guard-the-squash`, with `set -e` and a merge-base check:
```sh
git switch main && git merge --squash fix/<slug> && git commit
```
A semantic commit message, brief, in the owner's voice, no attribution lines. Never push without the owner saying so,
and never open a PR or issue.

## 5. Board
After a squash onto `main`, retitle the plan's section in `scratchpad/upnext.md` `squash merged to main as <sha> on
<date>` and update the header sha.

## 6. Clean up
Only after the commit and the board: `git worktree remove .claude/worktrees/<slug>` and `git branch -D fix/<slug>`, by
that exact name.

## Reply, at most 8 lines
What landed and where, conflicts and how each was resolved, checklist findings, board updated, next.
