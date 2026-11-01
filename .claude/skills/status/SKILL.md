---
name: status
description: "Orient: read scratchpad/upnext.md, reconcile it with git and the worktrees, report next steps. Use at session start, 'where are we', 'what's next'."
argument-hint: "[focus area, optional]"
---

The board is `scratchpad/upnext.md`, gitignored and local only. Git is reality. Report only what changes what the owner does next. Never look for or create a GitHub Status issue.

## Procedure

1. Read the board's header line (``Reconciled against `main` at `<sha>` on <date>.``), **Open plans**, and every section about a branch that is not merged yet. Skip **Smaller fills** and **Records** unless `$ARGUMENTS` asks.
2. Reality, one Bash call:
   ```sh
   git log --oneline <sha>..main; git status --short | head; git worktree list; git branch -vv | grep -v backup/
   ```
   Then `bash .claude/skills/status/env.sh`. A `BLOCKED` line goes in the reply before any suggestion, with the fix the owner runs.
3. Reconcile with `Edit`:
   - A commit on `main` since `<sha>` that lands a branch section → retitle that section as merged with its sha and date, the way the frame grid section reads.
   - An integration worktree (a named one under `.claude/worktrees/`, not `agent-*`) whose branch has no section → add a two-line section marked `unregistered`.
   - Header → `main`'s short sha and today's date.
   - Do not remove worktrees here. Count `agent-*` worktrees whose branch is merged into `main` or into a branch the board says landed, for the reply.
4. Reply, at most 12 lines, no headers: where things stand, branches waiting on the owner's go, open plans with their next unit, stale worktree count, then **Next** as 1 to 3 numbered options naming the skill (`/plan <slug>`, `/dispatch <plan> U<n>`, `/review <branch>`, `/merge <branch>`, `/ci`). Bias toward `$ARGUMENTS` when given.

## Rules
- Never paste the board back, the owner can open it.
- A dirty working tree is reported before any suggestion.
- `/status` only orients, it starts no work.
