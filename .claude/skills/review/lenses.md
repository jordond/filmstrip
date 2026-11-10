# Review lenses

The standing set `/review` checks a plan's diff against, once every unit is done.

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
- A caller-visible behaviour change without an edit to the matching page under `website/src/content/docs/guides/` is 🟡.
- Hostile input: every parser and reader of caller bytes has a named, tested bound on reads, bytes, counts and
  allocation. A length field is never trusted for a read or an allocation. A loop over caller data is cancellable.
- Concurrency and cache keys: races, waiters, what the key leaves out. A timeout on every wait.
- Each commit compiles on its own.
