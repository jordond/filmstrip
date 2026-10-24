---
name: wrap-up
description: "Checkpoint so a fresh session needs only /status: board, plans, worktrees, memory. Use at session end or before compaction."
---

Leave things so `/status` in a fresh session needs nothing from this conversation.

1. `git status --short` in the main checkout and every named integration worktree: uncommitted work is committed on its branch now, or listed in the reply as deliberately left, with why. Never commit to `main` without the owner's go, never push.
2. Board: reconcile `scratchpad/upnext.md` the way `/status` does (header sha, branch sections against `git worktree list` and `git branch -vv`). Anything a fresh session needs that only this conversation knows goes into the relevant section, at most 5 lines.
3. Plans: each unit's line on the board matches reality (`dispatched <date>`, `landed on fix/<slug> <date>, <sha>`), and each landed unit's report is folded into its section's verified line and loose ends.
4. Agents still running: leave them. Note `report pending, /merge on arrival` in their plan's section.
5. Worktrees: list `agent-*` worktrees whose branch has landed, as a cleanup line in the reply. Remove none without the owner's say.
6. Memory: write or update one only for a lasting lesson or a changed convention, never for session state, that lives on the board.
7. A question that reopens an owner decision is an open question on its plan, not a line left dangling on the board.

Reply, at most 8 lines: state, what was committed and where, what is in flight, stale worktrees, the first command for the next session.
