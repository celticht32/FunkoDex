# CLAUDE.md PATCH — add the registry-consult rule

This repo's `CLAUDE.md` is ~31KB and already documents several decisions inline
(the `catalog::`/`funko::` invariant, the two-scope streaming backup, etc.). This
patch does NOT duplicate those — it adds a short pointer so a new session knows to
CHECK `docs/DECISIONS.md` before acting, and records the supersede protocol.

## Where to paste

Insert the block below at the TOP of the existing `## Key architectural decisions`
section in `CLAUDE.md` (right after the `## Key architectural decisions` heading,
before `### Database — Couchbase Lite Community`).

## Block to paste

---

> **Decision registry — consult before acting.** Before making or reversing any
> architectural choice (data model, grouping, backup/restore, import/relink,
> build/toolchain, dependency pins, licensing/brand), check `docs/DECISIONS.md`
> for a binding entry (grep by keyword). If one exists, follow it or explicitly
> supersede it — set the old entry's status to "Superseded by DEC-NNN", move it to
> the Superseded section, and add the replacement; never silently contradict a
> recorded decision. If a session makes a NEW architectural decision, record it
> there. `docs/CONTEXT.md` is the hot-state file — read it first for "where we left
> off" (it's capped; if it exceeds ~30 lines something cold needs to move out).
> Toolchain pins are verified in DEC-010 — confirm any Compose/material3 symbol
> against project usage before writing (material3 is BOM-managed, not a literal pin).
>
> The detailed decisions below remain authoritative inline; `docs/DECISIONS.md` is
> the grep-friendly registry of the same calls plus the ones not captured here
> (e.g. the Gson tree-parse dead-end, accepted import edge cases, licensing/brand).

---

## Note on overlap

Some decisions (the `catalog::` invariant, streaming backup) now appear in BOTH the
inline section and `docs/DECISIONS.md`. That's acceptable short-term. The cleaner
end-state, if you want it later: thin the inline `## Key architectural decisions`
bodies down to one-line summaries + "see DEC-NNN", letting `docs/DECISIONS.md` hold
the detail. Not done here to avoid rewriting the 31KB file without your sign-off.
