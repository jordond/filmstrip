---
name: backlog
description: "Add one item to the scratchpad/upnext.md board. Use for 'add a todo', 'add to backlog', 'note that', 'remind me', follow-ups from an agent report."
argument-hint: "[what to remember]"
---

The backlog is `scratchpad/upnext.md`, gitignored. Never a GitHub issue, label or comment, and never the global `/add-todo`.

- Use `$ARGUMENTS`. Empty → ask once what to capture, then stop.
- No stop-gaps. An item that says "redo X properly later" or "replace this workaround" means X was a stop-gap. Say so in the reply instead of filing it, the proper fix belongs in X's plan now.
- Section: a loose end found on an unmerged branch goes in that branch's section. A small, unblocked item goes in **Smaller fills, unblocked, cheap**. Anything else goes in **Loose work, not in any plan**.
- Write it in the section's own list style: a bold short title where the section uses them, then what is wrong and where, with `file:line` when known. Wrap at 120 characters like the rest of the file. Do not invent scope.
- A matching item already exists → extend it instead of adding a duplicate.
- A cross-backend quantity (a number all four backends must agree on) says so in the item, so the plan computes it once.
- Reply with one line: `upnext: "<title>" (<section>)`.
