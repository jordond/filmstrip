---
name: plan
description: "Plan a change into scratchpad/plans/<slug>.md: what is true today per backend, the shared rule, units per module with owned files, tests and verification. Research is delegated. Orchestrator only."
argument-hint: "[slug or the change in a sentence]"
---

A unit is the contract a `builder` implements without asking a question. The plan says what is true, what the rule is, and how the work splits. The session running this skill writes only the plan and the board, never source.

## 1. Target
`$ARGUMENTS`: a slug, an `upnext.md` item, or the change in a sentence. Blank → list **Open plans** and **Loose work** from `scratchpad/upnext.md`, ask which, stop. An owner decision the plan rests on goes in the opening paragraph with its date, and alternatives turned down are named as not reopened.

## 2. Research, delegated, one agent
Only for gaps the board and existing plans in `scratchpad/plans/` leave open.
- One `analyst` covers both, a `scout` alone when only file locations are missing. `scout`: files owning the surface on each backend, existing shared helpers in `filmstrip-core` `internal/` and `filmstrip-transform`, test helpers to reuse. `file:line` table.
- `analyst`: how each of ffmpeg, Apple, media3 and the browser does it today, and any pinned library limit. At most 40 lines, `--note` only when the owner asks for a research note.

Read a source file yourself only where a wrong assumption would sink the plan.

## 3. Write `scratchpad/plans/<slug>.md`
Same shape as `plans/video-tail-hold.md`:
1. **What is true today**, one subsection per module or backend, read off the code at the base sha with `file:line`. Anything measured says **measured** and where. Anything unmeasured says so and gives the command that would measure it.
2. **The rule.** Any number all four backends must agree on is a shared quantity. Name the function that computes it once and the lowest module every consumer sees (`filmstrip-transform` when only backends read it, `filmstrip-core` when effects or core do). A backend diverges only for its own documented limit, clamped visibly and tested. Each backend limit the change meets says **name the limit** or **build the fix**, with a unit estimate for either.
3. **Preview parity.** What each preview engine does with the same edit, since preview and export must show the same frame.
4. **Units.** U0 is the shared quantity and lands first. One builder works the units in order, so split them for a
   clean commit each, not for parallel agents. Group units by machine (Mac or cerebro) so at most two builders are needed. Each unit: files owned, tests written failing first (asserting against the shared function, never a copied literal, and reading the middle of a range, not only its ends), and verification with the expected baseline counts, taken from the board's last verified counts, never a run before editing. The plan states its total unit count, which `/orchestrate` checks scope against. Each unit says where it runs: the Mac for Apple suites and ABI updates, cerebro (`scratchpad/remote/gradle.sh`) for everything else when the owner wants the Mac free.
5. **Verification rules, every unit.** Copy the standing set from the last plan and adjust: one test task per Gradle call, ffmpeg jvm and any Apple suite never together, `connectedAndroidDeviceTest` counts toward green for any preview, readback, thumbnail or export pixel path, `updateKotlinAbi` then `checkKotlinAbi` in separate calls when a public signature moves. Which suites, API levels and Apple targets a unit runs follows **Verification scope** in `/dispatch`.
6. **Overlap** with any open plan or unmerged branch touching the same files, and how to sequence it.
7. **Open questions**, each with a recommendation. A question that reopens an owner decision stops the plan: ask it, do not decide it.

One `AskUserQuestion` round at most, recommendation first. A plan past about 500 lines means the change is too big, split it.

## 4. Board
Add or update the plan's row under **Open plans** in `scratchpad/upnext.md`.

## Reply, at most 15 lines
The rule and its shared function, units with where each runs and their order, assumptions stated, open questions. Then `/dispatch <slug>`.
