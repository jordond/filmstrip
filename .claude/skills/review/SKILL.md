---
name: review
description: "Self-review a branch diff with filmstrip's lenses: cross-backend contracts, preview parity, KDoc rules, ABI. At most 15 severity-tagged lines plus a verdict, then fix them. Use before /merge."
argument-hint: "[branch | path... | blank for the working changes against main]"
---

Review the diff yourself, in this session. One review per plan, on the whole branch, after every unit is done.

## 0. Skip
No review when the branch changes no production code: only tests, CI, docs, build files or skills. Run the precheck,
fix its hits, and merge.

## 1. Precheck, one Bash
```sh
bash .claude/skills/review/precheck.sh <base> <branch or worktree>
```
It prints `path:line: reason` for mechanical hits in changed Kotlin: lines past 120 columns, leftover `// <slug>-u<n>`
or `-m<n>` markers, files past 500 lines, commas in Native test names. Fix them first.

## 2. Read the diff
`git diff --stat <base>...<branch>` first, then the diff file by file, production code before tests. Read any file
outside the diff from the plan worktree or `git show <branch>:<path>`, never the main checkout, which sits on `main`.

## 3. Judge
Lenses in priority: any the owner named, then `.claude/skills/review/lenses.md`, then correctness. Judge whether the
diff does what the plan says without breaking anything. List everything in one pass.
- A 🟠 names the failing input or test that shows it. Without one it is 🟡.
- A problem outside the plan's goal, or one that was there before the diff, is one line for the board, not a finding.

Write the findings as at most 15 lines `path:line: severity: problem. fix.` and a `verdict:` line.

## 4. Then
Fix 🔴, 🟠 and every 🟡 or 🔵 inside the plan's goal on the branch, as their own commit, and run the touched classes.
Lines outside the goal go to `scratchpad/upnext.md` via `/backlog`.

Reply with the lines, what was done about each, and the verdict.
