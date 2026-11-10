---
name: wrap-up
description: "Checkpoint and hand off: board, plans, worktrees, memory, then a continuation prompt in scratchpad/continue.md and a ping to the owner. Use at 150k to 200k context, at session end, or before compaction."
---

Leave things so a fresh session needs only the continuation prompt and the files it names.

1. `git status --short` in the main checkout and every named integration worktree: uncommitted work is committed on
   its branch now, or listed in the prompt as deliberately left, with why. Never commit to `main` without the owner's
   go, never push.
2. Board: reconcile `scratchpad/upnext.md` the way `/status` does (header sha, branch sections against
   `git worktree list` and `git branch -vv`). Each unit's line matches reality (`landed on fix/<slug> <date>, <sha>`).
3. Background work still running (a `/ci` lane, a remote Gradle call): note its log path and what to do with the result
   in the prompt.
4. Memory: write or update one only for a lasting lesson or a changed convention, never for session state.
5. Continuation prompt: write `scratchpad/continue.md`, at most 30 lines, self-contained for a session that has read
   nothing:
   - First line: the command to resume, usually `/orchestrate <slug>`.
   - Goal, the plan file, the worktree and branch with its head sha.
   - Done so far: units landed with shas.
   - In flight: the exact next step, and any half-finished reasoning or measurement only this conversation knows,
     with `file:line`.
   - Open decisions the owner already made this session, with what was decided.
   - Traps hit this session that the next one would hit again.
6. Ping: `PushNotification` with `context handoff ready: <slug>, next <step>`.

Reply with the prompt in one fenced block, ready to paste, and nothing else.
